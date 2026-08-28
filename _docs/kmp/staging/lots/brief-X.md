# Lot X — T1 peel: physio/pattern catalog after Lot W classifier

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `124b6a0fdf` (Lot W)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot W landed `PhysiologicalPhaseClassifier`, meal/endo engines, and HTR. `physio/pattern/*` was blocked on classifier `Output` / `PatternDefinition`. Dest has no `physio/pattern/`. This lot copies all **8** dump pattern files. Cap ~15.

**Compose-graph wall after this lot:** recursive engine still needs dump TickContext / Models adapters that hang on `HarmoniaSmbAuthorityDecision` / `PatientMode` / UAM Compose — not only `PatternCapKind`. Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (8 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/physio/pattern/<file>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/pattern/<file>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these eight exist at dest (checked 2026-08-28, HEAD `124b6a0fdf`).

Dump scan: no `android.*`, `File`, `org.json`, Compose, plugin, or `PkPdIntegration`. `PatternCapHold` KDoc names `DetermineBasalAIMI2` — backticks. `ContextIntent` / `ContextSnapshot` in Export are dest `context` types (not `model.ContextIntent`). `BeliefLeafId` is dest recursive. Lot W dest: `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `HyperTrajectoryHypoCredibility`. Lot O dest: `PhysioContextMTR` / `PhysioStateMTR`. Dest `InsulinStackingStance`.

Copy `PhysiologicalPatternId` + `PhysiologicalPatternModels` **before** `PhysiologicalPatternCatalog` (`Id.category` → Catalog; Catalog needs `PatternDefinition`). Detector / Policy / Hysteresis / CapHold / Export after Models.

| rel | why |
|---|---|
| `physio/pattern/PhysiologicalPatternId.kt` | enum + category getter |
| `physio/pattern/PhysiologicalPatternModels.kt` | `PatternDefinition` / readings / snapshot / `PatternCapKind`; dest classifier |
| `physio/pattern/PhysiologicalPatternCatalog.kt` | static defs |
| `physio/pattern/PhysiologicalPatternDetector.kt` | dest `PhysioStateMTR` / phases |
| `physio/pattern/PhysiologicalPatternPolicy.kt` | cap / cred scales |
| `physio/pattern/PhysiologicalPatternHysteresis.kt` | sticky dominant hold |
| `physio/pattern/PatternCapHold.kt` | HARD cap hysteresis |
| `physio/pattern/PhysiologicalPatternExport.kt` | JSON snapshot; dest meal engine + HTR + stacking stance + context |

---

## Skip — do not copy this lot

Do **not** copy recursive engine / TickContext / Models / adapters, `HarmoniaSmbAuthorityDecision`, `PatientMode`, `AuditorVerdict`, `DoseTerminalSnapshotBuilder`, Authority, `PkPdIntegration`, tick, `OpenAPSAIMIPlugin`. Five Lot L skips still need Compose / tree / `PkPdRuntime`.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 8 use `@Inject`. No Hilt. No `@IntKey(225)`.
2. **Log** — these 8 do not call `aapsLogger`. Do not add log calls.
3. **Time** — no `System.currentTimeMillis()`. Hysteresis / Export take `nowMs` parameters. Keep them.
4. **Format** — no `String.format`, no `"%.nf".format`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` if any appear. Do **not** add `aimiFmt3`.
5. **`@Volatile`** — if present, `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
6. **Explicit imports** — no FQ names at use site. Export must import `app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent` (not `model.ContextIntent`).
7. **KDoc** — `[docs/…]` paths → backticks. `[DetermineBasalAIMI2]` / parked tick → backticks. Dest-resolvable `[PhysiologicalPatternId]` / `[PhysiologicalPatternSnapshot.smbCapU]` may stay links.
8. **School English** — new or changed comments only.
9. **JSON** — keep kotlinx.serialization builders. No `org.json`. No `R.string`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-X.log`. Do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy Skip files to unblock compile.
- Overwrite Lot W dest classifier / HTR / meal engine.
- Register `@IntKey(225)`. Do not invent AIMI `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-X.md`: copied, skipped, rewrite notes, compile result. State that recursive engine still needs TickContext / Harmonia / `PatientMode`, not only `PatternCapKind`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
