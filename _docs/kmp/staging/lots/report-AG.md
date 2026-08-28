# Lot AG — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `35e3d262f4` (Lot AF)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `AuditorStatusTracker` / `LocalSentinel` were **not** overwritten. Dump `AuditorUIState` (`@ColorRes`) was **not** copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**`AuditorVerdict` is dest.** Dual-brain **helpers** were not this list. UAM builder stays dump. Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase`. Tick / plugin stay parked. Dest recursive engine is not live tick. `HoldAimiEngine` stays Hold.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| `patient/HarmoniaHarmonizer.kt` | dest tree / Harmonia / meal-certainty. `"%.2f".format` → `aimiFmt2` |
| `advisor/auditor/AuditorDataStructures.kt` | dest Harmonia / tree / `VerdictType`; this-lot Harmonizer. `AuditorVerdict` / `AuditorInput` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `advisor/auditor/model/AuditorUIState.kt` | `@ColorRes` / Android UI |
| DualBrain helpers / `DecisionModulator` | not this list |
| UAM builder / remaining Lot L / tick / plugin | parked |

---

## Rewrite notes

- Harmonizer log strings → `aimiFmt2`. Therapy factors unchanged.
- No Metro. No `aimiFmt3`. No `@IntKey(225)`.

---

## Compile

Redirect: `/tmp/aimi-lot-AG.log`.

Attempt 1 **FAILED**: leftover `"%.2f".format(correctionFragilityScore)`.

Attempt 2 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: Harmonizer + auditor DTOs only. No Compose UI. No tick.
- Next graph: DualBrain helpers if dest-type after `AuditorVerdict`, or remaining Lot L Compose wall.

Return DONE.
