# Lot AJ — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `e3a7732974` (Lot AI)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest Runtime / snapshot DTO / `fromAuthority` were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Causal kinetics modulator + insulin kinetics authority + intelligence snapshot builder are dest.** `class PkPdIntegration` stays dump. UAM builder stays dump. Auditor orchestrator still dump (LiveData + integration builder). Tick / plugin stay parked. Dest builder is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: dest `resolve` is still sync. Dump KDoc says the prediction IOB array is suspend-built by the plugin; dest only consumes the array. No new coroutines.

---

## Copied (3) — dest did not exist

| rel | notes |
|---|---|
| `pkpd/CausalKineticsModulator.kt` | dest `CausalStatePosterior`. `"%.2f".format` → `aimiFmt2` |
| `pkpd/InsulinKineticsAuthority.kt` | dest modulator / governors / Runtime / snapshot views |
| `orchestration/AimiIntelligenceSnapshotBuilder.kt` | dest authority + `fromAuthority`. Dropped unused dump `kotlin.math.abs` import |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `class PkPdIntegration` | Compose `readAimiBehaviorRuntimeProfile` |
| `SmbInstructionExecutor.kt` | `android.content.Context` |
| `AuditorOrchestrator.kt` / `AuditorDataCollector.kt` | LiveData / persistence host |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Format only on modulator conf. Kinetics / snapshot math unchanged.
- No Metro. No `aimiFmt3`. No `@IntKey(225)`.

---

## Compile

Redirect: `/tmp/aimi-lot-AJ.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: modulator + authority façade + snapshot builder only. Integration / executor / tick not copied.
- Dump-inherited unused `iobCobCalculator` / `exerciseFlag` on `BuildInput` stay; not introduced by this peel.
- Next graph: auditor host still blocked on LiveData + `PkPdIntegration`. Compose wall. Tick last.

Return DONE.
