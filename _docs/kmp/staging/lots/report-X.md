# Lot X — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `124b6a0fdf` (Lot W)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Lot W dest classifier / HTR / meal engine were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall after this lot:** recursive engine still needs dump TickContext / Models adapters that hang on `HarmoniaSmbAuthorityDecision` / `PatientMode` / UAM Compose — not only `PatternCapKind`. Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked.

---

## Copied (8) — dest did not exist

| rel | notes |
|---|---|
| `physio/pattern/PhysiologicalPatternId.kt` | enum + `category` getter → Catalog |
| `physio/pattern/PhysiologicalPatternModels.kt` | `PatternDefinition` / readings / snapshot / `PatternCapKind`; dest classifier + `BeliefLeafId`. `[docs/AIMI_HARMONIA_SMB_ARBITRATION.md]` → backticks |
| `physio/pattern/PhysiologicalPatternCatalog.kt` | static defs; dest `PatternDefinition` |
| `physio/pattern/PhysiologicalPatternDetector.kt` | dest `PhysioStateMTR` / phases. `"%.2f".format` → `aimiFmt2`, `"%.1f".format` → `aimiFmt1` |
| `physio/pattern/PhysiologicalPatternPolicy.kt` | cap / cred scales. `"%.2f".format` → `aimiFmt2` |
| `physio/pattern/PhysiologicalPatternHysteresis.kt` | sticky dominant hold; `nowMs` parameter kept. No `@Volatile` |
| `physio/pattern/PatternCapHold.kt` | HARD cap hysteresis. `DetermineBasalAIMI2` → backticks |
| `physio/pattern/PhysiologicalPatternExport.kt` | JSON snapshot; dest meal engine + HTR + stacking stance. `context.ContextIntent` / `ContextSnapshot` (not `model.ContextIntent`). Dropped unused `JsonPrimitive` |

No dest file was overwritten. Dest had no `physio/pattern/` before this lot.

Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| recursive engine / TickContext / Models / adapters | dump `HarmoniaSmbAuthorityDecision` / `PatientMode` / UAM Compose |
| `HarmoniaSmbAuthorityDecision` | UAM Compose |
| `PatientMode` / `TpoTriggerEngine` | dump `PatientMode` |
| `AuditorVerdict` / dual-brain auditor helpers | dump `AuditorVerdict` |
| `DoseTerminalSnapshotBuilder` / Authority | stay dump |
| `pkpd/PkPdIntegration.kt` | Compose `readAimiBehaviorRuntimeProfile` |
| tick / `OpenAPSAIMIPlugin` | parked |

Five Lot L skips still need Compose / tree / `PkPdRuntime`. **Not copied.**

---

## Rewrite notes

- Metro: none of these 8 have `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`. No `ApsPluginRegistrations`.
- Log: none of these 8 call `aapsLogger`. No log calls added.
- Time: no `System.currentTimeMillis()`. Hysteresis / Export take `nowMs` parameters. Kept.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. No `aimiFmt3`.
  - Detector HRV / IOB reasons: `"%.2f".format` → `aimiFmt2`; NGR delta: `"%.1f".format` → `aimiFmt1`. Explicit imports.
  - Policy `reasonSummary`: `"%.2f".format` on confidence → `aimiFmt2`.
- `@Volatile`: none of these 8 use it. Not added.
- Explicit imports: no fully qualified names at use site. Export imports `app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent` (not `model.ContextIntent`).
- KDoc: `[docs/AIMI_HARMONIA_SMB_ARBITRATION.md]` → backticks. `DetermineBasalAIMI2` in `PatternCapHold` → backticks. Dest-resolvable `[PhysiologicalPatternId]` / `[PhysiologicalPatternSnapshot.smbCapU]` stay links.
- School English: new or changed comments only. No mass-translate of dump comments.
- JSON: kotlinx.serialization builders kept. No `org.json`. No `R.string`.
- Therapy math unchanged except format / import / KDoc.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-X.log` | **BUILD SUCCESSFUL in 57s** (EXIT 0). Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain`. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **8**. Recursive engine still needs TickContext / Harmonia / `PatientMode`, not only `PatternCapKind`. Compile **BUILD SUCCESSFUL**.
