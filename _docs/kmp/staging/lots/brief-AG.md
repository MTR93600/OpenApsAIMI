# Lot AG — deliberate graph: Harmonia harmonizer + auditor verdict DTOs

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `35e3d262f4` (Lot AF)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AF landed the Authority applier + DTS builder. This lot is **2 dump copies**. Cap ~15.

**The cut:** `HarmoniaHarmonizer` only needs dest tree / `HarmoniaDecision` / `MealCertainty`. `AuditorDataStructures` (`AuditorVerdict` / `AuditorInput`) only needs this-lot Harmonizer + dest Harmonia / tree / `VerdictType`. Do **not** copy Compose auditor UI (`AuditorUIState` is a different dump file with `@ColorRes`).

Dest already has `AuditorStatusTracker` / `LocalSentinel`. Dest `advisor/auditor/` has no data-structures file.

**Compose-graph wall after this lot:** dual-brain **helpers** may still need other dump files — copy only this list. UAM builder stays dump. Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase`. Tick / plugin stay parked. Dest engine is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (2 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip and report**. Do not overwrite.

None of these two exist at dest (checked 2026-08-28, HEAD `35e3d262f4`).

| rel | why |
|---|---|
| `patient/HarmoniaHarmonizer.kt` | dest tree / Harmonia decision / meal-certainty. `"%.2f".format` → `aimiFmt2` |
| `advisor/auditor/AuditorDataStructures.kt` | dest Harmonia / tree / `VerdictType` / `AdvisorSeverity`; this-lot Harmonizer |

Copy order: Harmonizer, then auditor DTOs.

---

## Skip — do not copy this lot

Do **not** copy: `advisor/auditor/model/AuditorUIState.kt` (`@ColorRes`), DualBrain helpers unless already dest-type and on this list (they are **not** on this list), UAM builder, remaining Lot L, Compose screens, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

1. Harmonizer: `"%.2f".format` → `aimiFmt2`. `lowercase()` already has no Locale — keep.
2. `[MealCertainty]` dest same module — keep. `[docs/…]` → backticks.
3. No Metro. No `@IntKey(225)`. No `aimiFmt3`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AG.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy auditor UI / tick / plugin.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AG.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
