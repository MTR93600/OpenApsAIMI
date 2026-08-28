# Lot AC — deliberate graph: physiological tree + Harmonia + Models

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `0d59b8a503` (Lot AB)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AB landed UAM **DTO**, latent, posterior, `PatientMode`, TPO trigger, post-hypo delivery. This lot is **7 dump copies**. Cap ~15.

**The cut:** `PhysiologicalTree` only needs dest `PatientModeOrchestrator.Decision` + same-lot `InsulinIntent`. `HarmoniaDecision` / `HarmoniaAction` only need the tree snapshot. `HarmoniaSmbAuthorityDecision` needs dest `PatternCapKind` + same-lot `HarmoniaAction`. `RecursiveBeliefModels` only imports `HarmoniaSmbAuthorityDecision`. `MealCertainty.fromTreeAndEnvironment` needs the tree + Harmonia env. Two Lot L skips (`MealCorrectionContextResolver`, `T3cAutodriveBasalBridge`) become dest-type complete. Do **not** copy TickContext / Authority / UAM builder / Compose.

Dest already has `PatientModeOrchestrator`, `PatternCapKind`, `MealAbsorptionPhase`, thermal, WCycle, `ClampPkpdScenarioReconcile`, `PostHypoAggressiveRiseExit`, `PostHypoDeliveryAuthority`, UAM DTO, latent.

**Compose-graph wall after this lot:** TickContext still needs dump `AimiRiskEnvelope` (`DecisionPredictionSource` in Authority) + dump `SafetyPredictionTerminals` (resolver still needs Authority). Dual-brain auditor still needs `AuditorVerdict`. `DoseTerminalSnapshotBuilder` / Authority stay dump (tree / `MealCertainty` / `PostHypoDeliveryAuthority` become dest, but Authority still has dump UAM **builder** path / `PkPdRuntime` / other dump types — do **not** copy Authority until a later cut). UAM builder stays dump. Tick / plugin stay parked. Dump `compose/` **screens** stay T2.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (7 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these seven exist at dest (checked 2026-08-28, HEAD `0d59b8a503`). Dest `patient/` has snapshot / mode / posterior / event memory — no tree / Harmonia / meal-certainty. Dest `recursive/` has no Models. Dest `basal/` has T3C trajectory / planner — no Autodrive bridge. Dest root has no `MealCorrectionContextResolver`.

| rel | why |
|---|---|
| `patient/PhysiologicalTree.kt` | dest `PatientModeOrchestrator` / meal / thermal / WCycle; this-lot `InsulinIntent` |
| `patient/HarmoniaDecision.kt` | this-lot tree snapshot + `HarmoniaAction` / env / engine |
| `patient/HarmoniaSmbAuthorityDecision.kt` | dest `PatternCapKind`; this-lot `HarmoniaAction` / `InsulinIntent` |
| `patient/MealCertainty.kt` | dest clamp / rise-exit / meal phase; this-lot tree + Harmonia env / engine |
| `recursive/RecursiveBeliefModels.kt` | this-lot `HarmoniaSmbAuthorityDecision` only |
| `MealCorrectionContextResolver.kt` | dest snapshot / UAM DTO / latent / `PatientMode` / post-hypo; this-lot `HarmoniaAction` |
| `basal/T3cAutodriveBasalBridge.kt` | this-lot `GlobalPhysiologicalState` / `PhysiologicalRiskLevel` / tree snapshot |

Copy order (same lot, compile once at the end): tree + HarmoniaDecision + HarmoniaSmbAuthority together; MealCertainty; Models; MealCorrection; T3c bridge.

---

## Skip — do not copy this lot

Do **not** copy: `UamHypothesisStateBuilder`, `DecisionPredictionAuthority`, `SafetyPredictionTerminalsResolver`, `AimiRiskEnvelope`, `RecursiveBeliefTickContext`, recursive engine / adapters, `AuditorDataStructures` / `AuditorVerdict`, `PkPdIntegration`, `pkpd/PkpdAbsorptionGuard`, `smb/SmbDampingUsecase`, Compose screens, tick, `OpenAPSAIMIPlugin`.

Two Lot L skips remain (`PkpdAbsorptionGuard`, `SmbDampingUsecase`) — Compose `PkPdRuntime`. **Do not copy them.**

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 7 use `@Inject`. No Hilt. No `@IntKey(225)`.
2. **Log** — do not add `aapsLogger`.
3. **Time** — no `System.currentTimeMillis()`.
4. **Format** — `T3cAutodriveBasalBridge`: `"%.2f".format(Locale.US, …)` → `aimiFmt2`. Drop `java.util.Locale`.
5. **Locale** — `PhysiologicalTree` / `HarmoniaDecision`: `lowercase(Locale.US)` → `lowercase()`.
6. **JSON** — `HarmoniaSmbAuthorityDecision.putFiniteOrNull`: receiver is `JsonObjectBuilder` (inside `buildJsonObject`), not `JsonObject`. Split `Double else JsonNull` so kotlinx `put` sees one `JsonElement` branch. Comment may still name `org.json` (dump history).
7. **Explicit imports** — no FQ names at use site. Add `JsonObjectBuilder` import for `putFiniteOrNull`.
8. **KDoc** — `[docs/…]` paths → backticks. Dest-resolvable types may stay links.
9. **School English** — new or changed comments only.
10. **Do not** overwrite Lot AB dest files. Do not add keys.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-AC.log`. Do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy Authority, TickContext, UAM builder, Compose screens, tick, or plugin.
- Overwrite Lot AB dest files.
- Split `AdvisorModels`, `AuditorDataStructures`, `AimiControlCenterSupport`, `DecisionPredictionAuthority`.
- Register `@IntKey(225)`. Do not invent AIMI `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AC.md`: copied, skipped, rewrite notes (Locale, `aimiFmt2`, `putFiniteOrNull`), compile result. State that TickContext / Authority stay dump. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
