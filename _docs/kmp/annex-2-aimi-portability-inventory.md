# AIMI Kotlin-Multiplatform Portability Inventory

> **Note de consolidation.** Les comptes de fichiers/dépendances de cette annexe restent valides,
> mais « T0/pure Kotlin » ne signifie pas nécessairement « déplaçable inchangé » : les types AAPS
> transitifs, l'état, Native et le lifecycle doivent être vérifiés. La recommandation de convertir
> puis supprimer TFLite est remplacée par la conservation de `modelUAM.tflite` dans
> [`annex-5-ml-training-migration.md`](annex-5-ml-training-migration.md).

Branch: `dev_OAPSAIMI` (read-only inspection; working tree left on `kmp`).  
Root: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Date: 2026-08-25

## 0. Headline numbers

| metric | value |
|---|---|
| Kotlin files, `src/main` AIMI package | **441** |
| LOC, `src/main` AIMI package | **102,354** |
| Kotlin files, `src/test` AIMI package | **243** |
| LOC, `src/test` AIMI package | **31,962** |
| Combined (the "684 files / 133.6k LOC" figure) | **684 files / 134,316 LOC** |
| Top-level packages under `openAPSAIMI/` | **41** (+ 22 files at package root) |
| Largest single file | `DetermineBasalAIMI2.kt` — **18,886 LOC**, 357 `fun`, 12 nested classes |

## 1. Subsystem breakdown

### 1.1 By top-level package

| package | files | LOC | %LOC | purpose |
|---|---:|---:|---:|---|
| `(root)` | 22 | 24,602 | 24.0% | Loop entry point + plugin shell: DetermineBasalAIMI2, OpenAPSAIMIPlugin, NN core, Kalman, step service |
| `advisor` | 63 | 15,207 | 14.9% | Profile advisor, AI coaching, auditor, meal vision (LLM), oref ONNX/personal-ML pipeline, tuning |
| `physio` | 61 | 11,549 | 11.3% | Physiological context: Health Connect repository, feature extraction, hormonitor export, workers |
| `patient` | 18 | 4,376 | 4.3% | Harmonia decision engine, PhysiologicalTree, Harmonizer, body kinetics digests |
| `recursive` | 22 | 3,989 | 3.9% | RBT — recursive belief tree resolver, SMB authority arbitration, channel selection |
| `compose` | 10 | 3,915 | 3.8% | AIMI Control Center + PKPD settings Compose screens |
| `autodrive` | 18 | 3,793 | 3.7% | Autodrive (iLet-like) engine, state estimator, data lake, on-device neural trainer |
| `learning` | 10 | 3,565 | 3.5% | Basal NN learner, unified reactivity learner, ML training coordinator + WorkManager scheduler |
| `pkpd` | 27 | 3,519 | 3.4% | PK/PD kernel: insulin curves, DIA/peak learning, prediction kinetics, CSV logging |
| `context` | 11 | 3,459 | 3.4% | User intent/context module: parser, influence engine, LLM client, RecyclerView UI |
| `comparison` | 8 | 2,553 | 2.5% | Shadow comparison of AIMI SMB vs reference paths |
| `safety` | 24 | 1,993 | 1.9% | SafetyNet, insulin-load governor, meal safety, stacking surveillance |
| `basal` | 9 | 1,976 | 1.9% | Basal decision engine, T3C trajectory context, adaptive basal policy |
| `tpo` | 12 | 1,937 | 1.9% | Therapy-plan orchestrator: delta builder, LLM validator, notifications |
| `steps` | 11 | 1,731 | 1.7% | Step/activity providers: Health Connect steps sync, unified activity provider |
| `trajectory` | 5 | 1,402 | 1.4% | Phase-space trajectory models and history provider |
| `orchestration` | 13 | 1,377 | 1.3% | Tick context assembly, adaptation status builder, stage sequencing |
| `utils` | 7 | 1,051 | 1.0% | Storage helper, backup manager (SAF), AIMI logger |
| `ml` | 6 | 1,040 | 1.0% | Shared NN train/validate/publish core + model stores + SMB feature schema |
| `smb` | 8 | 894 | 0.9% | SMB instruction executor and demand shaping |
| `hormonitor` | 4 | 887 | 0.9% | Hormonitor study data reader + in-app Compose viewer |
| `wcycle` | 10 | 869 | 0.8% | Endocrine/menstrual cycle learner, amplitude governor, CSV logger |
| `risk` | 5 | 843 | 0.8% | Decision prediction authority, risk envelope |
| `ISF` | 5 | 760 | 0.7% | Sensitivity ratio estimator + ISF source telemetry |
| `scenario` | 9 | 748 | 0.7% | Scenario projection engine (two authoritative AIMI curves) |
| `release` | 7 | 669 | 0.7% | Hyper Trajectory Release (HTR) and release-authority evaluators |
| `quality` | 2 | 622 | 0.6% | Replay quality export/metrics |
| `activity` | 4 | 475 | 0.5% | EffortActivityBelief — sensor-driven effort/activity belief + exercise policy |
| `sos` | 2 | 454 | 0.4% | Emergency SOS (SMS) manager + permission activity |
| `model` | 5 | 418 | 0.4% | Core decision data models, constants, pump caps |
| `prediction` | 5 | 371 | 0.4% | PKPD/scenario prediction reconciliation and clamping |
| `llm` | 3 | 336 | 0.3% | LLM plumbing: Gemini model resolver, HTTP retry |
| `control` | 1 | 246 | 0.2% | Control-loop helper |
| `plugins` | 3 | 203 | 0.2% | AIMI decision-plugin contract/registry |
| `keys` | 2 | 114 | 0.1% | AIMI preference key definitions |
| `decision` | 2 | 106 | 0.1% | DecisionPolicy interfaces |
| `di` | 2 | 98 | 0.1% | Dagger modules (WCycle, physio) |
| `inflammatory` | 1 | 52 | 0.1% | Inflammatory-state signal |
| `validation` | 1 | 46 | 0.0% | Input/schema validation helper |
| `ports` | 1 | 44 | 0.0% | Port interfaces (PkpdPort etc.) |
| `extensions` | 1 | 34 | 0.0% | Kotlin extension helpers |
| `carbs` | 1 | 31 | 0.0% | Carb helper |
| **TOTAL** | **441** | **102,354** | 100% | |

### 1.2 20 largest files

| LOC | file | tier |
|---:|---|---|
| 18,886 | `DetermineBasalAIMI2.kt` | T2 |
| 2,316 | `advisor/AimiProfileAdvisorActivity.kt` | T2 |
| 2,282 | `OpenAPSAIMIPlugin.kt` | T2 |
| 1,214 | `advisor/AimiAdvisorService.kt` | T2 |
| 1,114 | `learning/BasalNeuralLearner.kt` | T2 |
| 1,016 | `compose/AimiControlCenterScreen.kt` | T2 |
| 994 | `recursive/RecursiveBeliefResolver.kt` | T0 |
| 984 | `physio/AIMIPhysioDataRepositoryMTR.kt` | T3 |
| 941 | `patient/PhysiologicalTree.kt` | T1 |
| 878 | `physio/AimiHormonitorStudyExporterMTR.kt` | T2 |
| 847 | `learning/UnifiedReactivityLearner.kt` | T2 |
| 802 | `learning/BasalMlTrainingCoordinator.kt` | T1 |
| 780 | `compose/PkpdSettingsUi.kt` | T2 |
| 776 | `compose/AimiControlCenterSnapshot.kt` | T2 |
| 682 | `pkpd/PkPdIntegration.kt` | T1 |
| 664 | `basal/BasalDecisionEngine.kt` | T2 |
| 650 | `physio/AIMIInsulinDecisionAdapterMTR.kt` | T2 |
| 648 | `context/ContextManager.kt` | T1 |
| 645 | `smb/SmbInstructionExecutor.kt` | T2 |
| 628 | `advisor/auditor/AuditorOrchestrator.kt` | T1 |

> `DetermineBasalAIMI2.kt` alone is **18,886 LOC = 18.5 %** of the whole AIMI main source set.
> The next 19 files together are 17,443 LOC. This one file is the migration project.

## 2. Portability tiers

Classification rule: import-anchored, with comments and string literals stripped first
(a naive grep for `Context` scores ~15 false positives from KDoc prose).

| tier | files | % files | LOC | % LOC | meaning |
|---|---:|---:|---:|---:|---|
| **T0** | 216 | 49.0% | 23,325 | 22.8% | pure Kotlin — moves to `commonMain` unchanged |
| **T1** | 137 | 31.1% | 29,799 | 29.1% | easy seam — `java.*` / `org.json` / Dagger / Rx only; mechanical rewrite |
| **T2** | 80 | 18.1% | 46,534 | 45.5% | needs `expect`/`actual` — Android Context, prefs, files, WorkManager, Compose/Views |
| **T3** | 8 | 1.8% | 2,696 | 2.6% | hard — TFLite, ONNX Runtime, Health Connect |
| **TOTAL** | **441** | 100% | **102,354** | 100% | |

### 2.1 The LOC number is misleading — measure seam *depth*

T2 looks like 45.5 % of LOC only because `DetermineBasalAIMI2.kt` (18,886) and
`OpenAPSAIMIPlugin.kt` (2,282) land there. Counting the lines that actually *touch* an
Android/androidx symbol (including `context.getString` / `R.string`):

| file | LOC | android-touching lines | share |
|---|---:|---:|---:|
| `DetermineBasalAIMI2.kt` | 18,886 | **244** | 1.3 % |
| `OpenAPSAIMIPlugin.kt` | 2,282 | **98** | 4.3 % |
| all 80 T2 files | 46,534 | **2,781** | **6.0 %** |
| all 8 T3 files | 2,696 | 231 | 8.6 % |

`DetermineBasalAIMI2.kt` has exactly **4 Android imports**:
`android.annotation.SuppressLint`, `android.content.Context`, `android.os.Environment` (unused —
no `Environment.` call site), `androidx.collection.LongSparseArray` (4 uses). The `Context` is a
constructor parameter used for **133 `context.getString(R.string.…)` calls** — i.e. localization,
not platform behaviour. Replace it with a `StringProvider` interface and the file's biggest
blocker is gone. Realistic reclassification of `DetermineBasalAIMI2.kt` after that single
refactor: **T1** (it still has `java.text`, `java.time`, `java.util`, `org.json`, `javax.inject`).

**Adjusted tiers after the `Context`→`StringProvider` + `java.time`→`kotlinx-datetime` seams:**

| tier | files | LOC | note |
|---|---:|---:|---|
| T0+T1 (portable core) | 353 | 53,124 (51.9 %) | as measured |
| T2 that is really T1-with-a-string-seam | ~25 | ~28,000 | `DetermineBasalAIMI2`, plugin, learners, services — ≤ 5 % android lines each |
| T2 genuinely platform (UI, workers, storage, notifications, sensors) | ~55 | ~18,500 | rewrite per platform |
| T3 | 8 | 2,696 (2.6 %) | see §4 |

### 2.2 Every T3 file

| LOC | file | offending import |
|---:|---|---|
| 984 | `physio/AIMIPhysioDataRepositoryMTR.kt` | `androidx.health.connect.client.HealthConnectClient` … |
| 394 | `steps/AIMIHealthConnectSyncServiceMTR.kt` | `androidx.health.connect.client.HealthConnectClient` … |
| 297 | `AimiModelHandler.kt` | `org.tensorflow.lite.Interpreter` |
| 293 | `physio/AIMIHealthConnectPermissionActivityMTR.kt` | `androidx.health.connect.client.HealthConnectClient` … |
| 231 | `physio/AIMIHealthConnectPermissionsHandlerMTR.kt` | `androidx.health.connect.client.HealthConnectClient` … |
| 191 | `advisor/oref/OrefOnnxScorer.kt` | `ai.onnxruntime.OnnxTensor` … |
| 176 | `steps/AIMIHealthConnectStepsProviderMTR.kt` | `androidx.health.connect.client.HealthConnectClient` … |
| 130 | `physio/AIMIHealthConnectPermissions.kt` | `androidx.health.connect.client.HealthConnectClient` … |

**T3 total: 8 files, 2,696 LOC (2.6 % of AIMI).** Split: Health Connect 6 files / 2,208 LOC,
TFLite 1 file / 297 LOC, ONNX Runtime 1 file / 191 LOC.

### 2.3 Every T2 file

| LOC | file | offending imports (first 3) |
|---:|---|---|
| 18,886 | `DetermineBasalAIMI2.kt` | `android.annotation.SuppressLint`, `android.content.Context`, `android.os.Environment` |
| 2,316 | `advisor/AimiProfileAdvisorActivity.kt` | `android.content.Intent`, `android.graphics.Color`, `android.graphics.Typeface` |
| 2,282 | `OpenAPSAIMIPlugin.kt` | `android.annotation.SuppressLint`, `android.content.Context`, `android.content.Intent` |
| 1,214 | `advisor/AimiAdvisorService.kt` | `android.content.Context` |
| 1,114 | `learning/BasalNeuralLearner.kt` | `android.content.Context`, `android.os.Environment` |
| 1,016 | `compose/AimiControlCenterScreen.kt` | `androidx.annotation.StringRes`, `androidx.compose.foundation.horizontalScroll`, `androidx.compose.foundation.layout.Arrangement` |
| 878 | `physio/AimiHormonitorStudyExporterMTR.kt` | `android.content.Context`, `android.os.Environment`, `android.os.SystemClock` |
| 847 | `learning/UnifiedReactivityLearner.kt` | `android.content.Context` |
| 780 | `compose/PkpdSettingsUi.kt` | `androidx.compose.foundation.clickable`, `androidx.compose.foundation.horizontalScroll`, `androidx.compose.foundation.layout.Arrangement` |
| 776 | `compose/AimiControlCenterSnapshot.kt` | `androidx.annotation.StringRes` |
| 664 | `basal/BasalDecisionEngine.kt` | `android.content.Context` |
| 650 | `physio/AIMIInsulinDecisionAdapterMTR.kt` | `android.os.Looper` |
| 645 | `smb/SmbInstructionExecutor.kt` | `android.content.Context` |
| 604 | `context/ContextLLMClient.kt` | `android.content.Context` |
| 599 | `advisor/data/T3cRuntimeHistoryReader.kt` | `android.os.Environment` |
| 510 | `advisor/AiCoachingService.kt` | `android.content.Context` |
| 509 | `comparison/AimiSmbComparator.kt` | `android.content.Context` |
| 485 | `compose/AimiControlCenterSupport.kt` | `androidx.annotation.StringRes` |
| 474 | `learning/BasalLearner.kt` | `android.content.Context` |
| 447 | `advisor/AimiModeSettingsActivity.kt` | `android.content.Context`, `android.graphics.Color`, `android.graphics.Typeface` |
| 439 | `physio/AIMILLMPhysioAnalyzerMTR.kt` | `android.content.Context` |
| 424 | `advisor/oref/OrefLocalPipeline.kt` | `android.content.Context` |
| 424 | `advisor/auditor/AuditorAIService.kt` | `android.content.Context` |
| 422 | `physio/AIMIPhysioManagerMTR.kt` | `android.content.Context`, `androidx.work.BackoffPolicy`, `androidx.work.Constraints` |
| 409 | `advisor/meal/MealAdvisorActivity.kt` | `android.content.Intent`, `android.graphics.Bitmap`, `android.graphics.Color` |
| 408 | `physio/AIMIPhysioContextStoreMTR.kt` | `android.content.Context` |
| 387 | `autodrive/learning/AutodriveNeuralTrainer.kt` | `android.content.Context` |
| 341 | `context/ui/ContextActivity.kt` | `android.os.Bundle`, `android.view.MenuItem`, `android.view.View` |
| 325 | `hormonitor/viewer/HormonitorViewerScreen.kt` | `android.os.Environment`, `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.Arrangement` |
| 315 | `autodrive/learning/AutodriveDataBackfiller.kt` | `android.content.Context` |
| 296 | `steps/UnifiedActivityProviderMTR.kt` | `android.os.Looper` |
| 287 | `ml/AimiSmbTrainer.kt` | `android.util.Log` |
| 284 | `sos/EmergencySosManager.kt` | `android.Manifest`, `android.annotation.SuppressLint`, `android.content.Context` |
| 281 | `physio/HealthContextRepository.kt` | `android.content.Context` |
| 274 | `utils/AimiStorageHelper.kt` | `android.content.Context`, `android.os.Environment` |
| 266 | `utils/AimiBackupManager.kt` | `android.content.Context`, `android.net.Uri`, `androidx.documentfile.provider.DocumentFile` |
| 259 | `advisor/meal/MealAdvisorCameraActivity.kt` | `android.Manifest`, `android.content.Context`, `android.content.Intent` |
| 223 | `llm/gemini/GeminiModelResolver.kt` | `android.content.Context`, `android.content.SharedPreferences`, `android.util.Log` |
| 218 | `advisor/meal/AIVisionProvider.kt` | `android.graphics.Bitmap` |
| 211 | `context/ui/ContextViewModel.kt` | `androidx.lifecycle.LiveData`, `androidx.lifecycle.MutableLiveData`, `androidx.lifecycle.ViewModel` |
| 203 | `advisor/auditor/ui/AuditorNotificationManager.kt` | `android.app.NotificationChannel`, `android.app.NotificationManager`, `android.app.PendingIntent` |
| 197 | `DetermineBasalInvocationCaches.kt` | `androidx.collection.LongSparseArray` |
| 196 | `advisor/auditor/ui/AuditorReportFormatter.kt` | `android.content.Context` |
| 195 | `advisor/auditor/ui/AuditorStatusIndicator.kt` | `android.content.Context`, `android.graphics.Color`, `android.util.AttributeSet` |
| 189 | `advisor/auditor/model/AuditorUIState.kt` | `androidx.annotation.ColorRes`, `androidx.annotation.DrawableRes` |
| 189 | `tpo/TpoOrchestrator.kt` | `android.content.Context` |
| 184 | `tpo/TpoLlmValidator.kt` | `android.content.Context` |
| 182 | `compose/AimiPkpdSettingsScreen.kt` | `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.layout.Column`, `androidx.compose.foundation.layout.fillMaxSize` |
| 180 | `tpo/TpoNotificationManager.kt` | `android.app.NotificationChannel`, `android.app.NotificationManager`, `android.app.PendingIntent` |
| 170 | `sos/AIMIEmergencySosPermissionActivityMTR.kt` | `android.Manifest`, `android.content.Intent`, `android.content.pm.PackageManager` |
| 156 | `advisor/tuning/TuningContextApplySupport.kt` | `android.content.Context` |
| 149 | `advisor/oref/OrefPersonalMlTrainer.kt` | `android.content.Context` |
| 145 | `advisor/diag/AimiDiagnosticsManager.kt` | `android.content.Context`, `android.content.SharedPreferences` |
| 139 | `wcycle/WCycleLearner.kt` | `android.content.Context`, `android.os.Environment` |
| 133 | `advisor/meal/GeminiVisionProvider.kt` | `android.graphics.Bitmap`, `android.util.Base64` |
| 126 | `learning/AimiMlTrainingScheduler.kt` | `android.content.Context`, `androidx.work.ExistingPeriodicWorkPolicy`, `androidx.work.ExistingWorkPolicy` |
| 122 | `advisor/auditor/ui/AuditorStatusLiveData.kt` | `androidx.lifecycle.LiveData`, `androidx.lifecycle.MutableLiveData` |
| 111 | `physio/AIMIPhysioWorkersMTR.kt` | `android.content.Context`, `androidx.work.CoroutineWorker`, `androidx.work.WorkerParameters` |
| 106 | `advisor/meal/DeepSeekVisionProvider.kt` | `android.graphics.Bitmap`, `android.util.Base64` |
| 92 | `pkpd/PkPdCsvLogger.kt` | `android.os.Environment`, `android.util.Log` |
| 91 | `advisor/meal/ClaudeVisionProvider.kt` | `android.graphics.Bitmap`, `android.util.Base64` |
| 89 | `context/ui/ContextIntentAdapter.kt` | `android.view.LayoutInflater`, `android.view.ViewGroup`, `androidx.recyclerview.widget.DiffUtil` |
| 85 | `advisor/meal/OpenAIVisionProvider.kt` | `android.graphics.Bitmap`, `android.util.Base64` |
| 85 | `compose/AimiControlCenterAdvisor.kt` | `androidx.annotation.StringRes` |
| 84 | `advisor/data/AdvisorHistoryRepository.kt` | `android.content.Context`, `android.content.SharedPreferences` |
| 81 | `StepService.kt` | `android.hardware.Sensor`, `android.hardware.SensorEvent`, `android.hardware.SensorEventListener` |
| 81 | `advisor/meal/FoodRecognitionService.kt` | `android.content.Context`, `android.graphics.Bitmap` |
| 66 | `llm/LlmHttpRetry.kt` | `android.util.Log` |
| 64 | `tpo/TpoUiSupport.kt` | `androidx.annotation.StringRes` |
| 60 | `di/WCycleModule.kt` | `android.content.Context` |
| 59 | `wcycle/WCycleCsvLogger.kt` | `android.content.Context`, `android.os.Environment`, `android.util.Log` |
| 54 | `context/ui/AimiPreferenceInfoScreen.kt` | `androidx.annotation.StringRes`, `androidx.compose.foundation.layout.padding`, `androidx.compose.foundation.rememberScrollState` |
| 51 | `autodrive/learning/AutodriveNeuralTrainerWorker.kt` | `android.content.Context`, `androidx.work.CoroutineWorker`, `androidx.work.WorkerParameters` |
| 50 | `steps/AIMIHealthConnectWorkerMTR.kt` | `android.content.Context`, `androidx.work.CoroutineWorker`, `androidx.work.WorkerParameters` |
| 43 | `autodrive/learning/AutodriveBackfillWorker.kt` | `android.content.Context`, `androidx.work.CoroutineWorker`, `androidx.work.WorkerParameters` |
| 37 | `advisor/oref/OrefUserInsightFormatter.kt` | `android.content.Context` |
| 34 | `learning/BasalMlTrainerWorker.kt` | `android.content.Context`, `androidx.hilt.work.HiltWorker`, `androidx.work.WorkerParameters` |
| 33 | `context/ui/PatientSignalGaugeBinder.kt` | `android.view.View` |
| 19 | `advisor/auditor/ui/AuditorReportActivity.kt` | `android.os.Bundle` |
| 15 | `learning/BasalMlWorkerDelegate.kt` | `androidx.work.ListenableWorker.Result` |

### 2.4 T0 distribution — where the pure logic lives

| package | T0 files | T0 LOC | T1 | T2 | T3 |
|---|---:|---:|---:|---:|---:|
| `advisor` | 20 | 3,206 | 16/2,999 | 26/8,811 | 1/191 |
| `recursive` | 20 | 3,122 | 2/867 | 0/0 | 0/0 |
| `physio` | 26 | 2,515 | 24/4,207 | 7/3,189 | 4/1,638 |
| `safety` | 21 | 1,684 | 3/309 | 0/0 | 0/0 |
| `pkpd` | 18 | 1,597 | 8/1,830 | 1/92 | 0/0 |
| `comparison` | 3 | 948 | 4/1,096 | 1/509 | 0/0 |
| `orchestration` | 9 | 921 | 4/456 | 0/0 | 0/0 |
| `risk` | 5 | 843 | 0/0 | 0/0 | 0/0 |
| `(root)` | 11 | 820 | 6/2,039 | 4/21,446 | 1/297 |
| `scenario` | 9 | 748 | 0/0 | 0/0 | 0/0 |
| `release` | 7 | 669 | 0/0 | 0/0 | 0/0 |
| `tpo` | 4 | 622 | 4/698 | 4/617 | 0/0 |
| `compose` | 4 | 591 | 0/0 | 6/3,324 | 0/0 |
| `patient` | 5 | 539 | 13/3,837 | 0/0 | 0/0 |
| `wcycle` | 6 | 502 | 2/169 | 2/198 | 0/0 |
| `autodrive` | 3 | 435 | 11/2,562 | 4/796 | 0/0 |
| `basal` | 4 | 429 | 4/883 | 1/664 | 0/0 |
| `context` | 1 | 379 | 4/1,748 | 6/1,332 | 0/0 |
| `trajectory` | 2 | 365 | 3/1,037 | 0/0 | 0/0 |
| `activity` | 3 | 361 | 1/114 | 0/0 | 0/0 |
| `prediction` | 4 | 278 | 1/93 | 0/0 | 0/0 |
| `smb` | 7 | 249 | 0/0 | 1/645 | 0/0 |
| `ISF` | 3 | 233 | 2/527 | 0/0 | 0/0 |
| `ml` | 1 | 197 | 4/556 | 1/287 | 0/0 |
| `learning` | 2 | 134 | 2/821 | 6/2,610 | 0/0 |
| `hormonitor` | 1 | 134 | 2/428 | 1/325 | 0/0 |
| `utils` | 2 | 124 | 3/387 | 2/540 | 0/0 |
| `keys` | 2 | 114 | 0/0 | 0/0 | 0/0 |
| `plugins` | 2 | 107 | 1/96 | 0/0 | 0/0 |
| `decision` | 2 | 106 | 0/0 | 0/0 | 0/0 |
| `model` | 3 | 105 | 2/313 | 0/0 | 0/0 |
| `inflammatory` | 1 | 52 | 0/0 | 0/0 | 0/0 |
| `llm` | 1 | 47 | 0/0 | 2/289 | 0/0 |
| `ports` | 1 | 44 | 0/0 | 0/0 | 0/0 |
| `steps` | 1 | 40 | 6/775 | 2/346 | 2/570 |
| `extensions` | 1 | 34 | 0/0 | 0/0 | 0/0 |
| `carbs` | 1 | 31 | 0/0 | 0/0 | 0/0 |

100 %-T0 packages (move today, zero work): `risk` (5/843), `scenario` (9/748),
`release` (7/669), `keys` (2/114), `decision` (2/106), `inflammatory` (1/52),
`ports` (1/44), `extensions` (1/34), `carbs` (1/31).  
Near-pure: `safety` 21/24 files T0, `recursive` 20/22, `orchestration` 9/13, `pkpd` 18/27.

## 3. Hostile-dependency census

Import-anchored where an import exists; comment/string-stripped code match otherwise.
LOC = total LOC of the files that contain the dependency (not lines affected).

| dependency | files | LOC of those files | example paths |
|---|---:|---:|---|
| import android.* | 72 | 43,881 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| import androidx.* | 41 | 33,873 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| javax.inject / dagger | 83 | 48,491 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| io.reactivex / RxBus | 2 | 2,548 | `OpenAPSAIMIPlugin.kt`, `utils/AimiBackupManager.kt` |
| org.json | 65 | 41,164 | `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`, `advisor/AimiAdvisorService.kt` |
| com.google.gson | 1 | 84 | `advisor/data/AdvisorHistoryRepository.kt` |
| tensorflow / tflite / onnx | 2 | 488 | `AimiModelHandler.kt`, `advisor/oref/OrefOnnxScorer.kt` |
| java.io.File | 33 | 32,330 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `learning/BasalNeuralLearner.kt` |
| java.io.* (any) | 38 | 31,363 | `DetermineBasalAIMI2.kt`, `learning/BasalNeuralLearner.kt`, `physio/AimiHormonitorStudyExporterMTR.kt` |
| java.util.concurrent | 43 | 34,231 | `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`, `physio/AIMIPhysioDataRepositoryMTR.kt` |
| kotlin.reflect | 14 | 24,103 | `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`, `context/ContextManager.kt` |
| java.text | 10 | 22,471 | `DetermineBasalAIMI2.kt`, `physio/AimiHormonitorStudyExporterMTR.kt`, `learning/UnifiedReactivityLearner.kt` |
| SharedPreferences | 4 | 736 | `sos/EmergencySosManager.kt`, `llm/gemini/GeminiModelResolver.kt`, `advisor/diag/AimiDiagnosticsManager.kt` |
| android Context | 55 | 38,654 | `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`, `advisor/AimiAdvisorService.kt` |
| R.string / @StringRes | 27 | 32,655 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| SimpleDateFormat | 13 | 26,511 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `advisor/AimiAdvisorService.kt` |
| joda | 0 | 0 | — |
| Thread / Executors | 2 | 490 | `advisor/auditor/AuditorAIService.kt`, `llm/LlmHttpRetry.kt` |
| System.currentTimeMillis | 80 | 49,029 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| java.util.Calendar/Date/TimeZone/Locale | 45 | 37,755 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| java.util.* (any) | 89 | 49,143 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| kotlinx.coroutines | 53 | 42,250 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |
| androidx.compose | 5 | 2,357 | `compose/AimiControlCenterScreen.kt`, `compose/PkpdSettingsUi.kt`, `hormonitor/viewer/HormonitorViewerScreen.kt` |
| androidx.work (WorkManager) | 9 | 1,246 | `physio/AIMIPhysioManagerMTR.kt`, `steps/AIMIHealthConnectSyncServiceMTR.kt`, `learning/AimiMlTrainingScheduler.kt` |
| androidx.health (Health Connect) | 6 | 2,208 | `physio/AIMIPhysioDataRepositoryMTR.kt`, `steps/AIMIHealthConnectSyncServiceMTR.kt`, `physio/AIMIHealthConnectPermissionActivityMTR.kt` |
| Handler/Looper | 6 | 3,950 | `OpenAPSAIMIPlugin.kt`, `physio/AIMIInsulinDecisionAdapterMTR.kt`, `steps/UnifiedActivityProviderMTR.kt` |
| android.hardware (sensors) | 2 | 340 | `advisor/meal/MealAdvisorCameraActivity.kt`, `StepService.kt` |
| Notification | 5 | 19,477 | `DetermineBasalAIMI2.kt`, `advisor/auditor/ui/AuditorNotificationManager.kt`, `tpo/TpoOrchestrator.kt` |
| java.net / HttpURLConnection | 8 | 2,011 | `advisor/AiCoachingService.kt`, `physio/AIMILLMPhysioAnalyzerMTR.kt`, `advisor/auditor/AuditorAIService.kt` |
| okhttp | 1 | 105 | `physio/thermal/OuraApiThermalClient.kt` |
| kotlinx.serialization | 1 | 2,282 | `OpenAPSAIMIPlugin.kt` |
| AAPS core (app.aaps.core.*) | 156 | 63,824 | `DetermineBasalAIMI2.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `OpenAPSAIMIPlugin.kt` |

Notable readings:

- **`joda` = 0 files.** No legacy date library anywhere. 
- **Rx = 2 files** (`OpenAPSAIMIPlugin.kt`, one other). AIMI is essentially Rx-free; the rest of AAPS is not.
- **`kotlinx.coroutines` = 53 files / 42,250 LOC.** Already the async model — KMP-native, zero cost.
- **Gson = 1 file / 84 LOC.** Serialization is `org.json` (65 files), not Gson. `org.json` is the
  single largest mechanical rewrite item — Milos already solved this pattern
  (`kotlinx.serialization`, see commit `9eb22e76a1` on `kmp`).
- **`SharedPreferences` = only 4 files.** AIMI goes through AAPS `Preferences`/`SP` interfaces
  (73 files) — one abstraction to `expect`/`actual`, not 73.
- **`Thread`/`Executors` = 2 files.** No hand-rolled threading to unwind.
- **`System.currentTimeMillis` = 80 files / 49,029 LOC.** Highest file count of any item, but it is
  a one-line `Clock.System.now().toEpochMilliseconds()` swap, ideally behind an injected clock.
- **`javax.inject`/Dagger = 83 files (75 `@Inject constructor`, 74 `@Singleton`, only 2 `@Module`).**
  Constructor injection dominates → mechanical; Dagger is not KMP but the *shape* of the code is.
- **`app.aaps.core.*` = 156 files / 63,824 LOC.** This is the real coupling, see §5.

## 4. The ML / model story — the verdict

AIMI runs **two ML stacks side by side**, and the pure-Kotlin one is the primary.

### 4.1 Pure-Kotlin neural network (primary)

`aimiNeuralNetwork.kt` — `class AimiNeuralNetwork(inputSize, hiddenSize, outputSize)`.
One hidden layer: `input -> z-score -> linear -> LeakyReLU -> (layer norm) -> (dropout) -> linear -> output`.
Adam optimiser, weight decay, seeded RNG, **on-device training** with train/validate/publish gating.
Imports: `java.io.File`, `kotlin.math.*`, `kotlin.random.Random`, `org.json.*`. **No Android, no native.**

| LOC | file | role |
|---:|---|---|
| 613 | `aimiNeuralNetwork.kt` | the network: forward, backprop, Adam, serialize |
| 412 | `ml/NeuralModelTrainer.kt` | shared train → validate → atomically publish core |
| 286 | `ml/AimiSmbTrainer.kt` | SMB-refinement head |
| 196 | `ml/SmbRefinementFeatureSchema.kt` | feature schema |
| 70 | `ml/AimiNeuralModelStore.kt` | JSON model persistence |
| 48 | `ml/TrainingCircuitBreaker.kt` | training safety |
| 39 | `TrainingConfig.kt` | hyperparameters |
| 22 | `ml/AimiSmbModelStore.kt` | SMB store |
| 1,113 | `learning/BasalNeuralLearner.kt` | basal head |
| 802 | `learning/BasalMlTrainingCoordinator.kt` | basal training pipeline |
| 148 | `advisor/oref/OrefPersonalMlTrainer.kt` | personal hypo/hyper MLP head |
| 386 | `autodrive/learning/AutodriveNeuralTrainer.kt` | autodrive head |
| 144 | `autodrive/learning/OnlineLearner.kt` | online update |
| **4,279** | **pure-Kotlin NN + trainers** | **100 % portable** |

Wider ML surface — `ml/` (6 files, 1,034) + `learning/` (10, 3,555) + `autodrive/learning/` (11, 1,747)
+ `advisor/oref/` (12, 1,456) + root `aimiNeuralNetwork.kt` (613) + `TrainingConfig.kt` (39) + `AimiModelHandler.kt` (297):
**42 files, 8,741 LOC**, of which only **488 LOC (5.6 %)** are native-bound (below) → **94.4 % of AIMI's ML code
is pure Kotlin and moves to `commonMain` as-is.**

### 4.2 Native-runtime ML (secondary)

| LOC | file | runtime | model location | in repo? |
|---:|---|---|---|---|
| 297 | `AimiModelHandler.kt` (`object AimiUamHandler`) | `org.tensorflow.lite.Interpreter` | `/Documents/AAPS/ml/modelUAM.tflite` (external storage, user-supplied) | `app/src/main/assets/modelUAM.tflite`, **4,504 bytes** |
| 191 | `advisor/oref/OrefOnnxScorer.kt` | `ai.onnxruntime.OrtSession` | `assets/oref/{hypo,hyper,bg_change}_lgbm.onnx` | yes — **440–442 bytes each** (stubs; `PLACE_ONNX_MODELS_HERE.txt` sits beside them) |
| **488** | **total native-bound** | | | |

Gradle (`plugins/aps/build.gradle.kts`):
```
implementation("org.tensorflow:tensorflow-lite:2.4.0")
implementation("org.tensorflow:tensorflow-lite-gpu:2.4.0")
implementation("org.tensorflow:tensorflow-lite-support:0.1.0")
implementation("org.tensorflow:tensorflow-lite-metadata:0.1.0")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
implementation("androidx.health.connect:connect-client:1.1.0")
```

### 4.3 Is the TFLite path live?

Yes, but narrowly. `AimiUamHandler.predictSmbUam(...)` has **one** call site,
`DetermineBasalAIMI2.calculateSMBFromModel()` (line 14,982), itself called twice
(lines 6,359 and 15,039) — the UAM SMB path with 18 float features. Every other reference
(9 sites across `OpenAPSAIMIPlugin.kt` + `DetermineBasalAIMI2.kt`) is
`confidenceOrZero()` / `clearCache()` / `close()` / `installConfidenceSupplier{}` — lifecycle and
a confidence scalar, not inference. `predictSmbUam` returns `0f` when the model file is absent
and callers already `coerceAtLeast(0f)`.

### 4.4 Verdict

**The inference engine is pure Kotlin. The native runtimes are 488 LOC of optional side-paths on
4 KB models.** ML is *not* the cost driver for this migration:

- **TFLite (297 LOC, 1 real call site, 4.5 KB model):** the cheapest option is to re-express
  `modelUAM` as an `AimiNeuralNetwork` JSON (a 4.5 KB TFLite graph is a handful of dense layers)
  and delete the dependency. Fallback: LiteRT has no supported iOS-from-KMP binding; you would
  need `expect`/`actual` with `TensorFlowLiteSwift` via cinterop. Estimated 297 LOC either way.
- **ONNX (191 LOC, 3 stub models of ~440 B):** ONNX Runtime does ship an iOS build
  (`onnxruntime-objc`/`onnxruntime-c`), so `expect`/`actual` + cinterop is viable. But the shipped
  models are placeholders — the feature is effectively dormant and can be `actual`-stubbed on iOS
  at zero behavioural cost.
- **Health Connect (6 files, 2,208 LOC) is the real T3 mass — 4.5× the ML native code.**
  iOS equivalent is HealthKit: same concepts (steps, HR, HRV, sleep, basal body temperature),
  entirely different API. This is a genuine per-platform rewrite of ~2,200 LOC behind a shared
  `HealthDataSource` interface. Note `physio/AIMIPhysioDataRepositoryMTR.kt` is 984 LOC of it.

## 5. External I/O surface

### 5.1 Filesystem

**37 files / 32,417 LOC** construct `File(...)` or read/write text. Base path is
`Environment.getExternalStorageDirectory()/Documents/AAPS` (5 literal occurrences).
Artifacts written:

| kind | files written |
|---|---|
| JSONL decision logs | `AIMI_Decisions.jsonl`, `AIMI_Decisions_Last24h.jsonl` |
| Hormonitor study JSONL | `AIMI_HORMONITOR_event_stream_v1.jsonl`, `…_daily_outcomes_v1.jsonl`, `…_shadow_contributions_v1.jsonl`, `…_loop_blackbox_v1.jsonl`, `…_dataset_qa_v1.jsonl`, `…_daily_state_v1.json` |
| CSV training records | `oapsaimi2_records.csv`, `oapsaimiML2_records.csv`, `basal_adaptive_records.csv`, `aimi_reactivity_analysis.csv`, `oapsaimi_wcycle.csv`, `backup_$timestamp.csv` |
| JSON model / state stores | `basal_adaptive_weights.json`, `t3c_brain_weights.json`, `personal_hypo_mlp.json`, `personal_hyper_mlp.json`, `aimi_basal_learner.json`, `aimi_unified_reactivity.json`, `basal_ml_training_state.json`, `sensitivity_ratio_state.json`, `circadian_meal_profile.json`, `physio_context.json`, `oapsaimi_wcycle_learned.json` |
| ML models | `ml/model.tflite`, `ml/modelUAM.tflite`, `assets/oref/*.onnx` |

**26 files** reference `.jsonl`/`.csv`. Migration path: one `expect` `AimiFileStore`
(okio `FileSystem` is the natural KMP answer) + `utils/AimiStorageHelper.kt` (274 LOC) and
`utils/AimiBackupManager.kt` (266 LOC, uses `androidx.documentfile` SAF — Android-only concept,
iOS needs `UIDocumentPicker`).

### 5.2 Android services / sensors

| surface | files | LOC | paths |
|---|---:|---:|---|
| Health Connect | 6 | 2,208 | see §2.2 |
| `android.hardware` step counter | 2 | 340 | `StepService.kt` (81), `steps/UnifiedActivityProviderMTR.kt` (296) |
| WorkManager | 9 | 1,246 | `learning/AimiMlTrainingScheduler.kt`, `physio/AIMIPhysioWorkersMTR.kt`, `autodrive/learning/Autodrive*Worker.kt`, `steps/AIMIHealthConnectWorkerMTR.kt`, `learning/BasalMlTrainerWorker.kt` |
| Notifications | 5 | 19,477 | `advisor/auditor/ui/AuditorNotificationManager.kt`, `tpo/TpoNotificationManager.kt`, `tpo/TpoOrchestrator.kt` |
| SAF / DocumentFile | 1 | 266 | `utils/AimiBackupManager.kt` |
| SMS / location (SOS) | 2 | 454 | `sos/EmergencySosManager.kt`, `sos/AIMIEmergencySosPermissionActivityMTR.kt` |
| `Handler`/`Looper` | 6 | 3,950 | `OpenAPSAIMIPlugin.kt`, `physio/AIMIInsulinDecisionAdapterMTR.kt`, `steps/UnifiedActivityProviderMTR.kt` |
| HTTP (LLM providers) | 8 | 2,011 | `java.net.HttpURLConnection` — 1 file uses okhttp; ktor is the KMP swap |

### 5.3 AAPS infrastructure that is itself not yet KMP

`app.aaps.core.*` is imported by **156 of 441 files (35 %) / 63,824 LOC (62 %)**. AIMI cannot
move before these move. Top consumers:

| AAPS symbol | AIMI files | LOC of those files | KMP status on `kmp` branch |
|---|---:|---:|---|
| `AAPSLogger` + `LTag` | 76 | 48,271 | interface — trivial |
| `Preferences` / `SP` (keys API) | 73 | 46,225 | interface, `SharedPreferences`-backed → needs `expect`/`actual` store |
| injected `Context` | 43 | 34,843 | must become `StringProvider` + platform ctx |
| `PersistenceLayer` (Room) | 21 | 31,396 | **Room → needs KMP Room or SQLDelight** — biggest external blocker |
| `ProfileFunction` | 8 | 26,023 | interface |
| `IobCobCalculator` | 8 | 22,859 | interface |
| `UiInteraction` | 6 | 21,919 | Android UI facade |
| `ResourceHelper` (`rh`) | 6 | 6,947 | 860 distinct `R.string` keys used across 15 files, 472 call sites |
| `ActivePlugin` | 3 | 21,387 | interface |
| `DateUtil` | 11 | 25,596 | → `kotlinx-datetime` |
| `FabricPrivacy` | 3 | 21,202 | Crashlytics — Android-only |
| `RxBus` | 2 | 2,548 | → `SharedFlow` |

**Localization is a first-class blocker: 860 distinct `R.string` keys, 472 call sites in 15 files.**
133 of those calls are inside `DetermineBasalAIMI2.kt` (they build the `rT.reason` console text).

## 6. UI surface

| kind | files | LOC |
|---|---:|---:|
| Compose (`androidx.compose`) | 5 | 2,357 |
| Android `Activity` subclasses | 8 | 4,254 |
| XML layouts (whole `plugins:aps` module) | 5 | — |
| `Fragment` subclasses | **0** | 0 |
| `R.layout.*` references from AIMI code | 4 (2 platform spinner items + 2 own layouts) | — |

**Compose files**

| LOC | file |
|---:|---|
| 1,016 | `compose/AimiControlCenterScreen.kt` |
| 780 | `compose/PkpdSettingsUi.kt` |
| 325 | `hormonitor/viewer/HormonitorViewerScreen.kt` |
| 182 | `compose/AimiPkpdSettingsScreen.kt` |
| 54 | `context/ui/AimiPreferenceInfoScreen.kt` |

(`compose/` also holds 5 non-Compose files — `AimiControlCenterSnapshot.kt` 776, 
`AimiControlCenterSupport.kt` 485, `AimiControlCenterAdvisor.kt` 85 etc. — state/DTO code that is
T0/T2-by-`@StringRes`-only.)

**Activity files (XML/Views, not Compose)**

| LOC | file |
|---:|---|
| 2,316 | `advisor/AimiProfileAdvisorActivity.kt` |
| 447 | `advisor/AimiModeSettingsActivity.kt` |
| 409 | `advisor/meal/MealAdvisorActivity.kt` |
| 341 | `context/ui/ContextActivity.kt` |
| 293 | `physio/AIMIHealthConnectPermissionActivityMTR.kt` |
| 259 | `advisor/meal/MealAdvisorCameraActivity.kt` |
| 170 | `sos/AIMIEmergencySosPermissionActivityMTR.kt` |
| 19 | `advisor/auditor/ui/AuditorReportActivity.kt` |

XML layouts on the branch: `activity_aimi_hc_permission.xml`, `activity_aimi_sos_permission.xml`,
`activity_context.xml`, `item_active_intent.xml`, `item_patient_signal_gauge.xml` — 5 total.
`advisor/AimiProfileAdvisorActivity.kt` (2,316 LOC) builds its UI **programmatically in Kotlin**
(`android.graphics.Color`, `Typeface`, `Gravity`, `LinearLayout`) — no layout file, 621
android-touching lines. It is the single worst UI file to port.

**Verdict:** **35.7 %** of AIMI's UI LOC is Compose (2,357 of 6,611 Compose+Activity). Compose
Multiplatform covers the Compose half; the 4,254 LOC of View-based Activities need a full
rewrite. Total AIMI UI is only **6.5 % of the codebase** — small compared to the 62 % that is
logic coupled to `app.aaps.core.*`.

## 7. Cost summary

| work item | LOC | difficulty |
|---|---:|---|
| Move T0 as-is to `commonMain` | 23,325 (216 files) | none |
| `org.json` → `kotlinx.serialization` (65 files) | — | mechanical, precedent exists on `kmp` |
| `java.time`/`java.util.Date`/`java.text` → `kotlinx-datetime` (45 + 10 files) | — | mechanical |
| `System.currentTimeMillis` → injected clock (80 files) | — | mechanical, high file count |
| Dagger → KMP DI (83 files, 75 `@Inject constructor`) | — | mechanical |
| `Context.getString`/`rh.gs` → `StringProvider` (860 keys, 472 sites, 15 files) | — | mechanical, high volume |
| File I/O → okio `expect`/`actual` (37 files) | — | one abstraction |
| WorkManager → platform schedulers (9 files) | 1,246 | `expect`/`actual` |
| Notifications, SAF backup, SOS SMS, step sensor | ~1,400 | `expect`/`actual` |
| View-based Activities → Compose Multiplatform | 4,254 | rewrite |
| **Health Connect → HealthKit** | **2,208** | **rewrite, no shortcut** |
| TFLite | 297 | delete (re-express model as `AimiNeuralNetwork` JSON) or cinterop |
| ONNX Runtime | 191 | `actual`-stub on iOS (models are placeholders) |
| **Blocked on AAPS core:** Room `PersistenceLayer`, `Preferences`, `ActivePlugin`, `UiInteraction` | 63,824 LOC of AIMI depends on it | out of AIMI's control |
