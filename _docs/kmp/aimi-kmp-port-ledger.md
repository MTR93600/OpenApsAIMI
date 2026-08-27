# AIMI KMP port ledger (Milos pattern)

Destination: `:plugins:aps` `commonMain` (`openAPSAIMI/`), types in `:core:interfaces` `commonMain`.
Same shape as OpenAPS SMB: algorithm in commonMain, Metro `@Inject`, no Hilt, no `android.util.Log`.

| Folder | Status | Notes |
|---|---|---|
| `core/interfaces` AIMI DTOs | done | `GlucoseStatusAIMI`, `OapsProfileAimi`, adaptation status |
| `model/` | done | `System.currentTimeMillis` → `aimiWallClockMs()`; `processDecision` does not enact |
| `ports/` | done | interfaces only |
| `extensions/` | done | `GlucoseStatusExtensionAIMI` |
| `carbs/` | done | dropped `@JvmStatic` |
| `decision/` | done | Metro not needed |
| `validation/` | done | `javax.inject` → Metro `@Inject` |
| `keys/` | partial | `AimiLongKey` only; `AimiStringKey` needs `R` + steps provider |
| `CircadianMath.kt` | done | commonMain; `java.util.Locale` not used |
| `TimestampedBgSample.kt` | done | commonMain |
| `ISF/` | partial | T0: `DynamicSensitivityPolicy`, `IsfAdjustmentEngine`, `IsfBlender`. Deferred: `DynIsfTrajectoryTuning`, `SensitivityRatioEstimator` (inject + JSON) |
| `prediction/` | partial | T0: `ClampPkpdScenarioReconcile`, `PredictionCurveMath`, `PredictionSanity`. Rest waits on PKPD / scenario graph |
| `safety/` | partial | T0 self-contained files. Deferred: `InsulinLoadGovernor`, `InsulinStackingStance` (physio/pkpd), `PostHypoDeliveryAuthority` (JSON + PatientMode), `SafetyRiskExportSnapshot` |
| `KalmanFilter.kt` | deferred | `AtomicBoolean` + `Dispatchers.IO` + `TddCalculator` |
| `patient/` | deferred | JSON / unported graph |
| `recursive/` | deferred | large import graph + JSON exporter |

Do not copy Advisor UI, SOS, Health Connect, TFLite into commonMain.
