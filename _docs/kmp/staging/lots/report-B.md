# Task B report — Peel JSON T1 AIMI files into commonMain

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Source: `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
Destination: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`  
Lot size: 46 files. Destinations were all missing before copy.

Status: **DONE_WITH_CONCERNS**  
Copied: **34**  
Skipped: **12**

Gradle was not run (brief). No commit, no push, no `androidMain` writes.

## Copied to commonMain (34)

Rewrites applied: `org.json` → `kotlinx.serialization.json` builders (`buildJsonObject` / `buildJsonArray` / `JsonArray`) and `OrgJsonCompat` readers (`optStringCompat`, `optLongCompat`, `optBooleanCompat`, `optJsonObjectCompat`, `optJsonArrayCompat`, `optDoubleCompat`, `optIntCompat`, `hasCompat`). `javax.inject.Inject` was already Metro in these copies or unused. `System.currentTimeMillis()` → `aimiWallClockMs()`. `String.format` / `Locale.US` / `"%.nf".format` → `aimiFmt0` / `aimiFmt1` / `aimiFmt2`. `java.util.concurrent.atomic.AtomicReference` → `kotlin.concurrent.atomics.AtomicReference`. Unused `java.time.Instant` import dropped.

- `advisor/AimiClinicalReportEngine.kt`
- `advisor/auditor/AuditorDataCollector.kt`
- `advisor/auditor/AuditorDataStructures.kt`
- `advisor/meal/MealVisionChatCompletionsParser.kt`
- `context/ContextIntentDeserializer.kt`
- `orchestration/DoseTerminalSnapshot.kt`
- `orchestration/IntelligenceSnapshotJson.kt`
- `patient/AimiCascadeArbitrationArtifacts.kt`
- `patient/BodyKineticsDigest.kt`
- `patient/CausalStatePosterior.kt`
- `patient/HarmoniaDecision.kt`
- `patient/HarmoniaSmbAuthorityDecision.kt`
- `patient/MealCertainty.kt`
- `patient/PatientEventMemory.kt`
- `patient/PatientModeOrchestrator.kt`
- `patient/PatientStateSnapshot.kt`
- `patient/PhysioLiveDigest.kt`
- `patient/PhysiologicalTree.kt`
- `physio/AIMIPhysioDataModelsMTR.kt`
- `physio/HealthContextSnapshot.kt`
- `physio/PhysioLatentState.kt`
- `physio/UamHypothesisState.kt`
- `physio/pattern/PhysiologicalPatternExport.kt`
- `physio/thermal/ThermalBeliefDigest.kt`
- `pkpd/PkpdSoftFloorPathMin.kt`
- `prediction/PredictionDivergenceAuditor.kt`
- `quality/ReplayQualityExport.kt`
- `quality/SmbBindingTrace.kt`
- `recursive/RecursiveBeliefAuthorityGate.kt`
- `recursive/UnfoldExporter.kt`
- `safety/PostHypoDeliveryAuthority.kt`
- `tpo/TpoEpisodeLedger.kt`
- `tpo/TpoModels.kt`
- `wcycle/WCycleBelief.kt`

## Skipped (12) — left in staging

| File | Why |
|---|---|
| `aimiNeuralNetwork.kt` | Brief: keep TFLite path; do not put NN core in commonMain. |
| `advisor/auditor/AuditorJsonlExport.kt` | Still needs `java.io.File` (`appendLine`). |
| `advisor/data/HarmoniaRuntimeHistoryReader.kt` | Still needs `java.io.File`. |
| `hormonitor/viewer/HormonitorReader.kt` | Still needs `java.io.File`. |
| `learning/BasalMlTrainingCoordinator.kt` | Still needs `java.io.File` / `AimiStorageHelper` (lot-A android host). |
| `tpo/TpoPersistence.kt` | Still needs `java.io.File` / `AimiStorageHelper`. |
| `context/ContextLLMClient.kt` | Still needs `android.content.Context`. |
| `physio/AIMILLMPhysioAnalyzerMTR.kt` | Still needs `android.content.Context` + `HttpURLConnection`. |
| `physio/thermal/OuraApiThermalClient.kt` | Still needs OkHttp. |
| `ISF/SensitivityRatioEstimator.kt` | Still needs `AimiStorageHelper` file I/O (lot-A). |
| `autodrive/learning/MechanismAttentionGate.kt` | Still needs `AimiStorageHelper` file I/O (lot-A). |
| `physio/CircadianMealProfileStore.kt` | Still needs `AimiStorageHelper` file I/O (lot-A). |

## Rewrite notes

- Reads use `OrgJsonCompat` so missing-key / JSON-null behaviour matches `org.json`.
- Writes use kotlinx builders. Nullable `put(key, value)` writes JSON `null` (same intent as `JSONObject.NULL`). Key order and spacing can differ from `org.json` `toString()`.
- `JSONObject(string)` parse → `Json.parseToJsonElement(string).jsonObject`.
- `getString` / `getLong` / `getDouble` / `getInt` / `getJSONObject` / `getJSONArray` became `getValue` + kotlinx accessors, still throwing when the key is missing (existing `try/catch` in deserializers kept).
- `TpoModels.jsonToMap` unwraps `JsonPrimitive` into Boolean / Int / Long / Double / String instead of `org.json` Java boxed types.
- `AimiCascadeArbitrationArtifacts` uses experimental `kotlin.concurrent.atomics` (`load` / `store`).

## Concerns (why not DONE)

1. Gradle was not run. Copied files are not compile-checked.
2. Several copied files still point at types that live in `androidMain` / lot-C (`AimiIntelligenceSnapshot`, `DecisionPredictionAuthority` / `PredictionAuthorityApplyResult`, `MealVisionJsonParser` / `FoodAnalysisPrompt`, `AIMIPhysioManagerMTR`, `ActivityManager`, `RecursiveBeliefExport`, `PhysiologicalPatternCatalog`, and others). They cannot compile on commonMain until those types move or the callers stay on Android.
3. File/HTTP/Android hosts were left in staging on purpose. JSON helpers inside them (`AuditorJsonlExport.toJsonObject`, `TpoPersistence` parse) were not split out.
