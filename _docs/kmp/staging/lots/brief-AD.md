# Lot AD — deliberate graph: prediction Authority + TickContext

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `a7c1eee7ca` (Lot AC)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AC landed the tree, Harmonia, `MealCertainty`, Models. Authority **imports** are dest. This lot is **4 dump copies**. Cap ~15.

**The cut:** `DecisionPredictionAuthority` needs dest `MealCertainty` / tree `GlobalPhysiologicalState` / UAM DTO / latent / posterior / `PostHypoDeliveryAuthority`. `AimiRiskEnvelope` needs this-lot `DecisionPredictionSource` + `SafetyPredictionTerminalsResolver`. TickContext needs dest envelope + dest `SafetyPredictionTerminals`. Do **not** copy the recursive **engine**, UAM builder, Compose, tick, or plugin.

Dest already has `IobConsensus` (`AimiRiskPhase` / `IobDecisionSource`), `PredictionPathMath`, scenario pair, meal engine, pattern snapshot, HTR classifier, `InsulinStackingStance`.

**Compose-graph wall after this lot:** recursive **engine** / adapters still need more dump types (check at report time). Dual-brain auditor still needs `AuditorVerdict`. UAM builder stays dump. `DoseTerminalSnapshotBuilder` may unlock if it only needed Authority — **verify in report; copy builder only if T1-clean and in this list**. This list does **not** include the DTS builder (still lives in dump `orchestration/DoseTerminalSnapshot.kt`; dest DTO already exists — do **not** overwrite dest DTO). Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (4 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these four exist at dest (checked 2026-08-28, HEAD `a7c1eee7ca`). Dest `risk/` has `IobConsensus` / `PredictionPathMath` only. Dest `recursive/` has Models — no TickContext.

| rel | why |
|---|---|
| `risk/DecisionPredictionAuthority.kt` | dest meal-certainty / tree state / UAM DTO / latent / posterior / post-hypo / scenario pair |
| `risk/SafetyPredictionTerminalsResolver.kt` | dest Harmonia engine / meal-certainty / scenario / meal safety; this-lot Authority |
| `risk/AimiRiskEnvelope.kt` | dest `AimiRiskPhase` / IOB source / hypo math / meal safety; this-lot Authority + terminals |
| `recursive/RecursiveBeliefTickContext.kt` | dest physio / curves / HTR / stacking; this-lot envelope + `SafetyPredictionTerminals` |

Copy order: Authority → terminals resolver → envelope → TickContext.

---

## Skip — do not copy this lot

Do **not** overwrite dest `orchestration/DoseTerminalSnapshot.kt` (DTO). Do **not** copy the dump builder into dest.

Do **not** copy: recursive engine / adapters / paradox, `UamHypothesisStateBuilder`, `AuditorDataStructures`, `PkPdIntegration`, remaining Lot L (`PkpdAbsorptionGuard`, `SmbDampingUsecase`), Compose screens, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy (Milos / merge rules)

1. **Metro** — none of these 4 use `@Inject`. No `@IntKey(225)`.
2. **KDoc** — TickContext `[DetermineBasalaimiSMB2]` → backticks (tick stays dump). `[docs/…]` → backticks.
3. **Explicit imports.** No FQ at use site.
4. Keep therapy math. No `aimiFmt3`. No new `project()` deps.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect to `/tmp/aimi-lot-AD.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Overwrite dest DTS DTO. Copy engine / tick / plugin / Compose / UAM builder.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AD.md`. State TickContext dest vs engine still dump. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
