# Lot AE — deliberate graph: recursive belief engine after TickContext

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `ac3c91a770` (Lot AD)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AD landed TickContext, Authority, envelope, terminals. This lot is **12 dump copies**. Cap ~15.

**The cut:** the recursive **engine** (collect → believe → resolve → export → pump-path bridge) only needs dest TickContext / Models / Harmonia arbiter / HTR evaluator / UAM **DTO** / `PatientMode`. Do **not** copy tick, plugin, UAM builder, or Compose. Dest already has TickContext, Models, Preferences, `RbtEpisodeMemory`, `WaveletBelief`, `BeliefLeafId`, `ChannelInterferenceOptimizer`.

**Compose-graph wall after this lot:** dual-brain auditor still needs `AuditorVerdict`. UAM builder stays dump. `DoseTerminalSnapshotBuilder` stays dump until `PredictionAuthorityApplier` lands (do **not** overwrite dest DTS DTO). Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase` (`PkPdRuntime` in Compose). Tick / plugin stay parked. Dump `compose/` **screens** stay T2. A dest TickContext + dest engine is **not** “AIMI runs on iOS”.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (12 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these twelve exist at dest (checked 2026-08-28, HEAD `ac3c91a770`). Dest `recursive/` already has TickContext / Models / Preferences / episode memory / wavelet / leaf id — **do not** recopy those.

| rel | why |
|---|---|
| `recursive/BeliefLeafAdapter.kt` | dest TickContext / `BeliefLeafId` / `BeliefLeafReading` |
| `recursive/BeliefLeafAdapterRegistry.kt` | dest TickContext / wavelet / stacking / HTR / scenario ids |
| `recursive/BeliefLeafRegistry.kt` | this-lot adapter registry |
| `recursive/RecursiveBeliefEngine.kt` | dest TickContext / wavelet; this-lot registry |
| `recursive/RecursiveBeliefParadox.kt` | dest TickContext / meal phase / stacking / trajectory |
| `recursive/CredibilityCascade.kt` | dest Models `BeliefScaleNode` / `ScaleTension` |
| `recursive/RecursiveBeliefResolver.kt` | dest Harmonia arbiter / load governor / stacking; this-lot engine |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dest HTR evaluator / meal phase |
| `recursive/RecursiveBeliefAuthorityGate.kt` | dest UAM DTO / `PatientMode` / latent / pattern / safety snapshots |
| `recursive/RbtChaosEvaluator.kt` | dest Models snapshot |
| `recursive/UnfoldExporter.kt` | dest Models export DTOs; this-lot authority gate |
| `recursive/RbtResolutionBridge.kt` | dest Models resolution / episode memory; this-lot chaos evaluator |

Copy order (same lot, compile once at the end): adapter + registry + registry entry; engine + paradox + cascade; resolver + release calculator + authority gate; chaos + unfold + bridge.

---

## Skip — do not copy this lot

Do **not** overwrite dest TickContext / Models / Preferences / `RbtEpisodeMemory`.

Do **not** copy: `UamHypothesisStateBuilder`, `DecisionPredictionAuthority` (already dest), `DoseTerminalSnapshotBuilder` (do not overwrite dest DTO), `PredictionAuthorityApplier`, `AuditorDataStructures`, `PkPdIntegration`, remaining Lot L, Compose screens, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 12 use `@Inject`. No `@IntKey(225)`.
2. **Format** — `"%.1f".format` / `"%.2f".format` → `aimiFmt1` / `aimiFmt2` (`BeliefLeafAdapterRegistry`, `RecursiveBeliefResolver`, `RbtChaosEvaluator`, `RbtResolutionBridge`). Do not add `aimiFmt3`.
3. **KDoc** — `[SafetyNet]` in the bridge → backticks. `[docs/…]` → backticks. Dest-resolvable same-module types may stay links. `[BeliefLeafAdapterRegistry]` is this lot — keep the link.
4. **Explicit imports.** No FQ at use site.
5. **School English** — new or changed comments only.
6. Do **not** overwrite Lot AD dest files. Do not add keys.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-AE.log`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy tick, plugin, UAM builder, Compose screens, Authority (already dest), dest DTS DTO overwrite.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AE.md`: copied, skipped, format rewrites, compile result. State that dest engine is not live tick. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
