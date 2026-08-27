# Task F report — Remaining unresolved API catalog + `LTag.AIMI`

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: `aimi-baseline-2026-08-26`  
Dump (read only): `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Tick first: `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`

Gradle: **not run** (brief: prefer not compiling `:core:interfaces`; lot E compiles it). No dump restore. No `:core:keys` edit. No PersistenceLayer edit. No commit, no push.

## Do (1) LTag

Added `AIMI("AIMI")` after `APS` in `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/logging/LTag.kt`. No other tags changed.

Dump still logs with `LTag.APS` (tick, plugin helpers). The new tag is for a later host pass.

## Scope of catalog

Assumes lot D lands every AIMI-prefixed key (`OApsAIMI*`, `Aimi*`, `ContextLLM`/`ContextMode`, `*Aimi*`) and lot E lands the three PersistenceLayer carb helpers (`getMostRecentCarbByDate`, `getMostRecentCarbAmount`, `getFutureCob`). Those are **excluded** below.

Tick dump already calls the three carb helpers (`DetermineBasalAIMI2.kt:1504–1509`). Lot E was already present in the working tree when this catalog was written; this lot did not touch that file.

---

## 1. Missing types / functions in `:core:interfaces` (not keys, not the 3 carb helpers)

| Symbol | Example |
|---|---|
| `APSResult.Algorithm.AIMI` | `OpenAPSAIMIPlugin.kt:383`, `DetermineBasalAIMI2.kt:2105`, `orchestration/AimiLoopTickRecovery.kt:96` |
| `APSResult.oapsProfileAimi` | `OpenAPSAIMIPlugin.kt:1569`, `advisor/oref/OrefFeatureBuilder.kt:58` |
| `APSResult.isHypoRisk` | freeze `APSResult.kt`; tick writes `rT.isHypoRisk` then UI reads APS result |
| `RT.isHypoRisk` | `DetermineBasalAIMI2.kt:7422` |
| `RT.trajectoryEnabled` | `DetermineBasalAIMI2.kt:2998`, `:16788` |
| `RT.trajectoryEnergy` | `DetermineBasalAIMI2.kt:4246`, `:16794` |
| `RT.trajectoryType` / `trajectoryCurvature` | `DetermineBasalAIMI2.kt:16789–16791` |
| `RT.contextEnabled` / `contextIntentCount` / `contextModulation` | `DetermineBasalAIMI2.kt:4116`, `:4379`, `:16522–16537` |
| `RT.learnersInfo` | `DetermineBasalAIMI2.kt:15694` |
| `RT.aimiAdaptationStatus` | `DetermineBasalAIMI2.kt:12411` |
| `RT.aimilog` + `aiAuditor*` (verdict/confidence/modulation/riskFlags) | freeze `RT.kt`; tick/UI path |
| `NotificationId.HYPO_RISK_ALARM` | `DetermineBasalAIMI2.kt:7426`, `:15473` |
| `NotificationId.AIMI_AUDITOR_INSIGHT` | `advisor/auditor/ui/AuditorNotificationManager.kt:64`, `:85` |
| `EventPreferenceChange` | `OpenAPSAIMIPlugin.kt:51`, `:247`, `:276` — **absent from entire kmp tree** |
| `EventAimiCloudBackupTrigger` | `utils/AimiBackupManager.kt:12`, `:56` |
| `EventAimiCloudBackupResult` | `utils/AimiBackupManager.kt:11`, `:147` |
| `CloudBackupConstants` (`CLOUD_PATH_AIMI`) | `utils/AimiBackupManager.kt:8`, `:113` |
| `PersistenceLayer.deleteLastEventMatchingKeyword` | `therapy.kt:121–131` (meal/sleep/sport tags) |
| `PersistenceLayer.insertOrUpdateStepsCount` (singular default) | `steps/AIMIHealthConnectSyncServiceMTR.kt:209`, `steps/AIMIPhoneStepsSyncServiceMTR.kt:119` — kmp has only `insertOrUpdateStepsCounts` (plural) |
| `NSSettingsStatus` | unused import `advisor/diag/AimiDiagnosticsManager.kt:8` (dead; still unresolved if kept) |

`AimiAdaptation*` DTO enums already exist in `AimiAdaptationStatus.kt`. `TirCalculator` / `TIR` restored APIs are in section 5.

### Lot D prefix misses (still fail after lot D as written)

These are freeze AIMI keys used by tick/plugin but **do not** match brief-D prefixes (`OApsAIMI` / `Aimi` / `ContextLLM` / `*Aimi*`):

| Symbol | Example |
|---|---|
| `DoubleKey.autodriveMaxBasal` | `DetermineBasalAIMI2.kt:3659` (18 dump hits), `OpenAPSAIMIPlugin.kt` pref add |
| `DoubleKey.meal_modes_MaxBasal` | `DetermineBasalAIMI2.kt:2858` (17 dump hits) |
| `BooleanKey.OApsxdriponeminute` | `OpenAPSAIMIPlugin.kt:1971` (freeze key `key_use_Aimi_xdripOM`) |
| `DoubleKey.AvgTdd` | commented only, `advisor/diag/AimiDiagnosticsManager.kt:139` — ignore unless uncommented |

Dump also still imports `app.aaps.core.keys.R as CoreKeysR` (`OpenAPSAIMIPlugin.kt:61`, `:2037` `aimi_tube_advanced_title`). kmp keys use `KeysStrings`; this compiles on androidMain only if lot D adds that XML name.

---

## 2. Missing types in `:core:data` / `:core:objects`

No extra dump types found after keys + carb helpers.

Checked and **present**: `OrgJsonCompat.*Compat` extensions, `SourceSensor.advancedFilteringSupported`, `TB.convertedToAbsolute` / `plannedRemainingMinutes`, `SC` / `HR` / `UE` / `CA`.

---

## 3. Missing Android host APIs (list, do not port)

| API | Example |
|---|---|
| `android.content.Context` | `OpenAPSAIMIPlugin.kt:174`, `DetermineBasalAIMI2.kt` `context.getString` |
| `android.os.Looper` | `OpenAPSAIMIPlugin.kt:6` |
| `android.os.Environment` (Documents/AAPS) | `DetermineBasalAIMI2.kt:5`, `AimiModelHandler.kt:38` |
| `WorkManager` / `CoroutineWorker` | `learning/AimiMlTrainingScheduler.kt:8`, `physio/AIMIPhysioManagerMTR.kt:9`, `learning/BasalMlTrainerWorker.kt` |
| `HealthConnectClient` | `steps/AIMIHealthConnectStepsProviderMTR.kt:4`, `physio/AIMIPhysioDataRepositoryMTR.kt:4` |
| `org.tensorflow.lite.Interpreter` | `AimiModelHandler.kt:7` |
| `ai.onnxruntime.*` | `advisor/oref/OrefOnnxScorer.kt:3–6` |
| `SmsManager` + `LocationManager` | `sos/EmergencySosManager.kt:8–10`, `:238–265` |
| `android.hardware.camera2` | `advisor/meal/MealAdvisorCameraActivity.kt:13` |
| `androidx.collection.LongSparseArray` | `DetermineBasalAIMI2.kt:6` (tick TIR/TDD caches) |

Lot A already added TFLite / Health Connect / ONNX androidMain deps. These stay Android host; do not port to commonMain.

---

## 4. `AfrezzaMaxBasalConstraints` / `AfrezzaMaxBasalRate`

**Still absent** on this branch (no type, no key, no plugin).

Dump plugin already no-ops both sites:

- `OpenAPSAIMIPlugin.kt:1691` `// TODO(kmp): AfrezzaMaxBasalConstraints not on kmp yet` (was `AfrezzaMaxBasalConstraints.apply`)
- `OpenAPSAIMIPlugin.kt:1741` same TODO (was `add(DoubleKey.AfrezzaMaxBasalRate)`)

Lot D will not add `AfrezzaMaxBasalRate` (not an AIMI-prefixed key).

---

## 5. `TirCalculator` / `TIR`

Already restored on kmp: `calculate` (suspend, same as freeze), `averageTIR`, `calculateHour`, `calculateDaily`, `belowPct` / `inRangePct` / `abovePct`.

Dump still calls **only** those. Tick examples: `DetermineBasalAIMI2.kt:1458–1475`, `:7406`. Also `DetermineBasalInvocationCaches.kt:129`, `advisor/AimiAdvisorService.kt:291–322`.

No extra TIR API beyond the restored set.

---

## 6. `R.string.*` still missing from `plugins/aps/.../aimi_strings.xml`

Dump uses **857** unique `R.string.*`. **198** names are nowhere in repo English resources (not in `aimi_strings.xml`, not in `plugins/aps` `strings.xml`, not in `core/ui` / `core/interfaces`).

17 others live in `core.ui.R` / `core.interfaces.R` (`no_profile_set`, `format_insulin_units`, …). Dump often binds `app.aaps.plugins.aps.R`, so those still fail unless the call site uses the other `R` (plugin already does this for `advanced_settings_title` via `core.ui.R` at `OpenAPSAIMIPlugin.kt:1764`).

Tick + plugin names that are **nowhere** (cap; full 198 is the same family):

| Symbol | File:line |
|---|---|
| `reason_max_iob` | `DetermineBasalAIMI2.kt:2595` |
| `autodrive_status` | `DetermineBasalAIMI2.kt:3114` |
| `reason_maxsmb` | `DetermineBasalAIMI2.kt:5679` |
| `reason_cgm_calibrating` / `reason_bg_data_old` / `reason_cgm_flat` | `DetermineBasalAIMI2.kt:5698–5703` |
| `reason_hypo_guard` | `DetermineBasalAIMI2.kt:6401` |
| `hypo_risk_notification_text` | `DetermineBasalAIMI2.kt:7427` |
| `reason_iob_max` / `reason_set_temp_basal` | `DetermineBasalAIMI2.kt:7691`, `:7729` |
| `reason_insulin_required` / `reason_max_smb` / `reason_microbolus` | `DetermineBasalAIMI2.kt:7814–7845` |
| `smb_disabled` / `smb_enabled_*` | `DetermineBasalAIMI2.kt:12306–12374` |
| `lgs_triggered` / `lgs_triggered_min_pred` | `DetermineBasalAIMI2.kt:12501`, `:12554` |
| `reason_prebolus_bfast1` … `reason_prebolus_snack` | `DetermineBasalAIMI2.kt:16172–16244` |
| `hypo_risk_notification_title` | `OpenAPSAIMIPlugin.kt:1845` |
| `user_preferences` | `OpenAPSAIMIPlugin.kt:1776` |
| `endo_preferences_title` | `OpenAPSAIMIPlugin.kt:2094` |
| `training_ml_*_modes_preferences` (7 names) | `OpenAPSAIMIPlugin.kt:2141–2217` |
| `autodrive_preferences` / `autodrive_prebolus_variables` | `OpenAPSAIMIPlugin.kt:2230`, `:2253` |
| `reason_early_meal` / `safety_cut_tbr` | `basal/BasalDecisionEngine.kt:272`, `:308` |
| `sos_sms_title_*` / `sos_sms_footer_*` | `sos/EmergencySosManager.kt:191–204` |

Also missing from `aimi_strings.xml` but used by dump via `plugins.aps.R`: `autodrive_max_basal_title`, `meal_modes_max_basal_title` (`compose/AimiControlCenterSnapshot.kt:689–690`).

---

## 7. `dagger` / Hilt / `android.util.Log` still in dump

**Hilt / dagger:** no `import dagger` left. `OpenAPSAIMIPlugin.kt:104` still `import javax.inject.Provider` (same as `LoopPlugin`; keep). `learning/BasalMlTrainerWorker.kt:36` mentions `@HiltWorker` in a comment only.

**`android.util.Log` still in dump:**

| File | Notes |
|---|---|
| `AimiModelHandler.kt:4` | TFLite path |
| `StepService.kt:5` | |
| `ml/AimiSmbTrainer.kt:3` | |
| `pkpd/PkPdCsvLogger.kt:4` | |
| `wcycle/WCycleCsvLogger.kt:5` | |
| `llm/LlmHttpRetry.kt:3` | |
| `llm/gemini/GeminiModelResolver.kt:5` | |
| `advisor/AimiAdvisorService.kt:378+` | `Log.d/w/e("AIMI_ADVISOR", …)` |
| `advisor/AiCoachingService.kt:168` | |
| `advisor/auditor/AuditorAIService.kt:218` | |
| `advisor/auditor/model/StateTransitionManager.kt:45` | |
| `physio/AIMILLMPhysioAnalyzerMTR.kt:95` | |
| `physio/AIMIHealthConnectPermissionActivityMTR.kt:7` | |
| `sos/AIMIEmergencySosPermissionActivityMTR.kt:10` | |

Tick and plugin use `AAPSLogger` + `LTag.APS`, not `android.util.Log`.

---

## Concerns

1. Catalog is static (grep + freeze `git show`). Not compile-verified.
2. Lot D prefix list can miss `autodriveMaxBasal` / `meal_modes_MaxBasal` / `OApsxdriponeminute` — tick will not compile without them.
3. `EventPreferenceChange` is missing from kmp generally, not only AIMI.
4. 198 reason/SMB/SOS strings are freeze `plugins/aps` strings that never landed in `aimi_strings.xml`.
5. Dump still logs `LTag.APS`; `LTag.AIMI` is unused until a later pass.

## Counts

- LTag: `AIMI` added after `APS`
- Catalog rows above: **~80** (198 strings summarized)
- Status: `DONE_WITH_CONCERNS`
