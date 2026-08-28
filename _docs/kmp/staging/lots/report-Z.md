# Lot Z — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `08bc621dae` (Lot Y)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Lot Y dest files, Lot X dest `physio/pattern/*`, and Lot W dest classifier / HTR / meal engine / DTS DTO were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**TickContext is still blocked** on dump `AimiRiskEnvelope` (`DecisionPredictionSource` in Authority) and dump `SafetyPredictionTerminals` (resolver file) — Lot Y types did not unblock it. **Models still needs dump `HarmoniaAction`**. Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked. Dump `compose/` **screens** stay T2 (`@Composable` / `androidx.compose`).

---

## Copied (2) — dest did not exist

Dest had **no** `compose/` folder. Package kept as dump `app.aaps.plugins.aps.openAPSAIMI.compose` (not `androidx.compose`). `PkpdPresetProfiles` copied first (`detectPkpdInsulinPreset` returns `PkpdInsulinPreset`).

| rel | notes |
|---|---|
| `compose/PkpdPresetProfiles.kt` | insulin preset clamps + learned-state reclamp; dest `DoubleKey` / `Preferences`. Same-package `[PkpdLearningPace]` / `[PkpdCorrectionPrudence]` / `[PkpdTailPrudence]` kept as links after `PkpdSettingsSupport` landed |
| `compose/PkpdSettingsSupport.kt` | dest `PkpdSmbTailDamping` + dest `AimiAction.PreferenceUpdate` + dest keys. **`pkpdPrefsSnapshotFrom` omitted.** Dump `PkpdPrefsSnapshot` / `BooleanKey` imports dropped. File KDoc: snapshot mapper stays dump until `AdvisorModels` is T1-clean. `AdvisorModels` **not** split |

No dest file was overwritten. Zero dest-exists skips.

Screens in dump `compose/` were **not** copied (`PkpdSettingsUi`, `AimiPkpdSettingsScreen`, Control Center).

---

## Skipped — not this list

| rel | reason |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `AimiRiskEnvelope` + dump `SafetyPredictionTerminals` |
| `risk/AimiRiskEnvelope.kt` | dump Authority + `MealCertainty` + resolver. Not split |
| `risk/SafetyPredictionTerminalsResolver.kt` | dump Harmonia / `MealCertainty` / Authority. Not split |
| `risk/DecisionPredictionAuthority.kt` | UAM / tree / latent / posterior. Not split |
| `recursive/RecursiveBeliefModels.kt` | dump `HarmoniaSmbAuthorityDecision` / dump `HarmoniaAction` |
| `patient/HarmoniaSmbAuthorityDecision.kt` / `HarmoniaDecision.kt` | dump `HarmoniaAction` / tree |
| recursive engine / adapters / paradox / cascade | TickContext / Models |
| `compose/PkpdSettingsUi.kt` / `AimiPkpdSettingsScreen.kt` / `AimiControlCenterScreen.kt` | `@Composable` / `androidx.compose`. T2 |
| `compose/AimiBehaviorRuntimeProfile.kt` | dump `AimiAutonomyMode` + `R.string` |
| `MealCorrectionContextResolver.kt` / `T3cAutodriveBasalBridge.kt` / `PkpdAbsorptionGuard.kt` / `SmbDampingUsecase.kt` | remaining Lot L skips (4) |
| UAM / `PhysioLatentState` / PatientMode / Authority / tick / plugin | Compose / dump graph / parked |

Four Lot L skips stay **four**. **Not copied.** `AdvisorModels` **not** split.

---

## Rewrite notes

- Metro: neither file uses `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`. No `ApsPluginRegistrations`.
- Log: neither file calls `aapsLogger`. No log calls added.
- Time: no `System.currentTimeMillis()`.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. No `aimiFmt3`.
- `@Volatile`: neither file uses it. Not added.
- Explicit imports: no fully qualified names at use site. `PkpdSettingsSupport` imports dest `PkpdSmbTailDamping` and dest `AimiAction`. Same-package `PkpdInsulinPreset` — no FQ dest name.
- KDoc: dest-resolvable `[PkpdSmbTailDamping]` / `[DoubleKey]` stay links. Same-package `[PkpdLearningPace]` / `[PkpdCorrectionPrudence]` / `[PkpdTailPrudence]` / `[PkpdInsulinPreset]` stay links. Parked mapper named with backticks (`pkpdPrefsSnapshotFrom` / `PkpdPrefsSnapshot` / `AdvisorModels`). Dest `PkpdSmbTailDamping` KDoc **not** edited. “Compose screen” / UI polarity comments left as-is.
- School English: new or changed comments only (file KDoc on `PkpdSettingsSupport`).
- JSON: no `org.json`. No `R.string`.
- **Omit:** `pkpdPrefsSnapshotFrom` and unused `PkpdPrefsSnapshot` / `BooleanKey` imports. `AdvisorModels` not split.
- Therapy math unchanged except omit + file KDoc + unused import drop.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-Z.log` | **BUILD SUCCESSFUL in 1m 1s** (EXIT 0). Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain`. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **2**. TickContext still blocked on dump `AimiRiskEnvelope` / `SafetyPredictionTerminals` (Lot Y types did not unblock it). Dump `compose/` screens stay T2. Compile **BUILD SUCCESSFUL**.
