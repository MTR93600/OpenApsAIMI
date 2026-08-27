# Task A report — AIMI androidMain host

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Gradle: not run (controller compiles after all agents). No commit, no push.

## Files copied

Lot-A listed 90 kotlin files. Destination `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/` had none of them at copy time (no overwrites).

| Result | Count | Notes |
|---|---:|---|
| Copied (lot-A kotlin) | 89 | From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>` |
| Dropped | 1 | `di/AIMIStepsProviderModuleMTR.kt` — empty Hilt scan-only module, no `@Provides` |
| Skip existing dest | 0 | |
| Missing source | 0 | |
| Layouts copied | 5 | Freeze English XML into `plugins/aps/src/androidMain/res/layout/` |

`OpenAPSAIMIPlugin.kt` and `DetermineBasalAIMI2.kt` **are** in `lot-A.txt` (brief-C assumed they were lot-C; `lot-C.txt` does not list them). Both were copied.

## Metro edits

- `javax.inject.Inject` → Metro: staging sources already used `dev.zacsweers.metro.Inject`. No remaining `import javax.inject.Inject` in lot-A dest files. `javax.inject.Provider` kept (same as `LoopPlugin`).
- `dagger.Reusable` / `@Reusable`: none in lot-A sources.
- `di/WCycleModule.kt`: Hilt `@Module` / `@InstallIn(SingletonComponent)` → Metro `@ContributesTo(AppScope::class)` + `@BindingContainer` + `@Provides`, same shape as `ApsPluginRegistrations.kt`.
- `di/AIMIStepsProviderModuleMTR.kt`: dropped (no `@Provides`).
- `learning/BasalMlTrainerWorker.kt`: `@HiltWorker` + `dagger.assisted` → Metro `@AssistedInject` / `@Assisted` / `@AssistedFactory` + `MetroWorkerCreator`, assisted names `context` / `params` (required by Metro).
- `@ApplicationContext` removed on `AuditorNotificationManager`, `TpoOrchestrator`, `TpoNotificationManager` (unqualified `Context` is already provided).
- Five activities that extended deleted `TranslatedDaggerAppCompatActivity` now extend `MetroAppCompatActivity` and wrap locale via `LocaleHelper`:
  - `advisor/AimiModeSettingsActivity.kt`
  - `advisor/AimiProfileAdvisorActivity.kt`
  - `advisor/auditor/ui/AuditorReportActivity.kt`
  - `advisor/meal/MealAdvisorActivity.kt`
  - `context/ui/ContextActivity.kt`
- New `di/AimiMemberInjectors.kt`: `@FeatureMemberInjectors` `@IntoMap` `@ClassKey` entries for those five activities (same pattern as `SourceMemberInjectors.kt`).
- `DetermineBasalAIMI2.kt`: copied as-is, **not edited** (already Metro `@Inject` / `@SingleIn` in staging).

## Host wiring

- `plugins/aps/build.gradle.kts` **androidMain only**:
  - `org.tensorflow:tensorflow-lite:2.4.0`
  - `androidx.health.connect:connect-client:1.1.0`
  - `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
- Layouts from `aimi-baseline-2026-08-26`:
  - `activity_aimi_hc_permission.xml`
  - `activity_aimi_sos_permission.xml`
  - `activity_context.xml`
  - `item_active_intent.xml`
  - `item_patient_signal_gauge.xml`
- Plugin registered in `ApsPluginRegistrations.kt`: `@IntKey(225)` `openApsAimiEntry(OpenAPSAIMIPlugin)`.

## Plugin compile no-ops (lot-A owns `OpenAPSAIMIPlugin.kt`)

- Unused `HasAndroidInjector` constructor param + import removed (`:plugins:aps` has no `dagger.android`).
- `AfrezzaMaxBasalConstraints.apply(...)` skipped with `// TODO(kmp): AfrezzaMaxBasalConstraints not on kmp yet`.
- `DoubleKey.AfrezzaMaxBasalRate` preference add skipped with the same TODO (key not on this branch).

## Skipped / not done

- Gradle compile (forbidden).
- `AppWorkersGraph` / `:app` binding for `BasalMlTrainerWorker.Factory` (out of lot-A; worker will not be built by `MetroWorkerFactory` until `:app` contributes it).
- Freeze also had `tensorflow-lite-gpu` / `-support` / `-metadata`; brief asked only the three androidMain deps above.
- Lot-B / lot-C / `commonMain` / ledger: not edited.
- `DetermineBasalAIMI2.kt`: no Metro pass (already converted in staging).

## Concerns / blockers for the controller

1. **Worker graph:** `BasalMlTrainerWorker.Factory` is on the worker class but not bound in `app/.../AppWorkersGraph.kt`. WorkManager will fall back until `:app` adds `@WorkerKey(BasalMlTrainerWorker::class)`.
2. **Afrezza:** basal-cap call and preference key are no-ops until `AfrezzaMaxBasalConstraints` / `DoubleKey.AfrezzaMaxBasalRate` exist on kmp.
3. **HasAndroidInjector:** unused constructor dep removed so Metro can construct `OpenAPSAIMIPlugin`. If something still expected that injector, it is gone.
4. **Parallel lot-C:** destination already had other AIMI files after copy (lot-C running in parallel). Lot-A did not overwrite existing dest files.
5. **Not tested:** no Gradle, no install, no runtime check of activity member injection or Health Connect / TFLite / ONNX.

## Counts for controller

- Status: `DONE_WITH_CONCERNS`
- Lot-A kotlin copied: **89**
- Layouts copied: **5**
- Dropped: **1** (`di/AIMIStepsProviderModuleMTR.kt`)
- Plugin registration: **yes** (`@IntKey(225)`)
