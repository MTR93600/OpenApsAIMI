# Lot AO — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `375fe54bf6` (Lot AN)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**SMB refinement feature schema is dest.** Trainers / model stores stay dump (`File`). `class PkPdIntegration` stays dump. UAM builder stays dump. Tick / plugin stay parked. Dest schema is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Schema is sync math.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `ml/SmbRefinementFeatureSchema.kt` | dest patient/physio types. Feature order unchanged |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AimiSmbTrainer` / model stores | `File` |
| `AimiAdaptationStatusBuilder` | dump `BasalLearner` snapshots |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Copy as-is. No Metro. No `aimiFmt3`. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AO.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: feature vector only. Trainers not copied.
- Next graph: Compose wall. Learner snapshots still dump. Tick last.

Return DONE.
