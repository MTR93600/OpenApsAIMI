# Merge/docs report — kmp into AIMI study (2026-08-28)

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `45f91c9407`  
Merge: `903d725489` (`kmp` @ `a084d564c5` into this branch)  
AIMI APIs: `9a9292513d`  
Staging dump: `45f91c9407`  
Freeze: `aimi-baseline-2026-08-26` (`1ae418e106`)

No production Kotlin edited. No commit. No push.

Teacher for AIMI lots: OpenAPS SMB in `:plugins:aps` `commonMain` (`OpenAPSSMBPlugin`, `DetermineBasalSMB`).  
A `commonMain` compile is **not** “AIMI runs on iOS”.

---

## What the merge changed that AIMI MUST copy

| Area | After merge | AIMI action |
|---|---|---|
| Source sets | `commonMain` + `androidMain` + `androidHostTest`. iOS targets declared; `iosMain` often empty | Put algorithm in `commonMain`. Host in `androidMain`. Do not invent AIMI `iosMain`. |
| Gradle | `GenerateKeyStringsTask` → `ApsStrings` / `KeysStrings`. No new inter-module `project()` deps | Add English strings under `src/androidMain/res/values/*.xml`. Do not add module edges. |
| DI | Metro `@Inject` / `@SingleIn(AppScope)` / `@ContributesIntoMap` + `@IntKey` | No Hilt. Match SMB in `commonMain`. |
| Strings | `TextResolver` + generated `TextRef.Named` | Never `R.string` / `ResourceHelper` in `commonMain`. |
| Prefs | `EventPreferenceChange(String)`, `KeyValueStore` in common, `SP` `@StringRes` on Android | Pass `SomeKey.key`. |
| Time | `kotlin.time.Clock.System` | Use `aimiWallClockMs()` or that Clock. |
| Log | `AAPSLogger` + `LTag` | No `android.util.Log` in `commonMain`. Prefer `LTag.AIMI`. |
| JSON | `kotlinx.serialization` writes + `OrgJsonCompat` reads | No `org.json` in `commonMain`. |
| NotificationId | id = `ordinal`. Append only | Do not insert in the middle. |
| One+ / Libre 3 | Android Metro plugins `@IntKey(446)` / `@IntKey(447)` | Already here. Do not re-port in AIMI lots. GATT stays Android. |

`:plugins:aps` gradle is already the kmp shape. Copy **that**, not `:plugins:source` (see concerns).

---

## Import / API rules (good vs bad)

All “good” lines exist today in OpenAPS SMB or in AIMI `commonMain` that already landed.

### 1. Metro inject in commonMain

Bad:
```kotlin
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel
```

Good:
```kotlin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
```

### 2. TextResolver, not ResourceHelper, in commonMain

Bad:
```kotlin
import app.aaps.core.interfaces.resources.ResourceHelper
class OpenAPSSMBPlugin @Inject constructor(override val rh: ResourceHelper, ...)
```

Good (`OpenAPSSMBPlugin`):
```kotlin
import app.aaps.core.interfaces.resources.TextResolver
class OpenAPSSMBPlugin @Inject constructor(override val rh: TextResolver, ...)
```

`androidMain` may still take `ResourceHelper` (Autotune). Dexcom/Libre 3 hosts may too — they are Android only.

### 3. Generated string objects, not `R.string`

Bad:
```kotlin
import app.aaps.plugins.aps.R
.pluginName(R.string.openapssmb)
```

Good:
```kotlin
import app.aaps.plugins.aps.ApsStrings
.pluginName(ApsStrings.openapssmb)
```

`:core:keys` uses `KeysStrings` in the same package (no extra import). `:core:ui` uses `CoreUiStrings`. Never write `TextRef.Named("aps", "openapssmb")` by hand.

### 4. Preference titles are `TextRef`, not `titleResId`

Bad (still in the dump `keys/AimiStringKey.kt`):
```kotlin
import app.aaps.plugins.aps.R
titleResId = R.string.OApsAIMI_PregnancyDueDate_title
```

Good (`BooleanKey` AIMI entries already on this branch):
```kotlin
title = KeysStrings.pref_title_oaps_aimi_pregnancy
```

`ApsIntentKey` uses `title = ApsStrings.pref_title_aimi_control_center` and `urlRef`, not `urlResId`.

### 5. `GenerateKeyStringsTask` owns the names

Bad: a new AIMI string only in Kotlin, or only in a file the generator does not see.

Good: English `<string name="…">` under `src/androidMain/res/values/` (`strings.xml` **or** `aimi_strings.xml` — the task reads every xml in `values/`). Then use `ApsStrings.that_name` / `KeysStrings.that_name`. The Kotlin name must be a legal identifier.

### 6. No new inter-module project deps

Bad:
```kotlin
implementation(project(":plugins:automation"))
```

Good: keep `:plugins:aps` on the modules it already has (`:core:data`, `:core:interfaces`, `:core:keys`, `:core:nssdk`, `:core:objects`, `:core:utils`, `:core:ui`). Move shared types into those cores if needed; discuss first.

### 7. Clock in commonMain

Bad:
```kotlin
val now = System.currentTimeMillis()
```

Good (already in `AimiWallClock.kt`):
```kotlin
import kotlin.time.Clock
fun aimiWallClockMs(): Long = Clock.System.now().toEpochMilliseconds()
```

Injected `dateUtil.now()` is also fine (SMB plugin). `DetermineBasalSMB` uses `kotlinx.datetime.Instant` / `TimeZone` for local hour — that is allowed; `java.util.Date` / `java.time` is not in `commonMain`.

### 8. Preference change events

Bad (old Android helper):
```kotlin
event.isChanged(rh, R.string.key_use_AimiPregnancy)
```

Good (dump plugin already matches the merge):
```kotlin
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
if (event.isChanged(BooleanKey.AimiPhysioAssistantEnable.key)) { ... }
```

Class is `EventPreferenceChange(val key: String)`.

### 9. Typed keys vs raw SP resource ids

Bad in commonMain:
```kotlin
sp.getBoolean(R.string.key_enable_basal, false)
```

Good:
```kotlin
preferences.get(BooleanKey.OApsAIMIEnableBasal)
```

`KeyValueStore` is the string-key half (common). `SP` `@StringRes` overloads stay in `androidMain`.

### 10. Logging

Bad:
```kotlin
import android.util.Log
Log.d("AIMI", msg)
```

Good:
```kotlin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
aapsLogger.debug(LTag.AIMI, msg)
```

`LTag.AIMI` already exists. Dump `AimiLogger` still uses `LTag.APS` — switch on move.

### 11. JSON

Bad:
```kotlin
import org.json.JSONObject
val name = obj.optString("k")
```

Good (`TpoModels` in `commonMain`):
```kotlin
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
val name = obj.optStringCompat("k")
```

Reads go through `OrgJsonCompat` so missing-key behaviour stays like `org.json`. Writes use kotlinx builders.

### 12. NotificationId ordinals — append only

Bad: insert a new id above `HYPO_RISK_ALARM` (shifts every later ordinal / system notification id).

Good: add at the **end** of `NotificationId`. Current tail:

- `DEXCOM_ONEPLUS_DIR_ACCESS_LOST`
- `LIBRE3_DIR_ACCESS_LOST`
- `HYPO_RISK_ALARM`
- `AIMI_AUDITOR_INSIGHT`

### 13. Explicit imports

Bad:
```kotlin
fun composeIcon() = app.aaps.core.ui.compose.icons.IcPluginOpenAPS
```

Good (`OpenAPSSMBPlugin`):
```kotlin
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
```

### 14. Plugin map keys stay androidMain

Bad: `@IntKey(225)` on a `commonMain` file (plugin maps are an Android / JVM Metro concern).

Good: algorithm class in `commonMain`; registration in `androidMain` `ApsPluginRegistrations` (SMB is `@IntKey(220)`). AIMI is planned `@IntKey(225)` **later**, when the plugin file actually lives in `androidMain`. Do not steal 210/220/230.

### 15. One+ / Libre 3 — Metro, Android only

Bad:
```kotlin
@HiltAndroidApp
class DexcomOnePlusPlugin
```

Good (already on this branch):
```kotlin
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(446)
@SingleIn(AppScope::class)
class DexcomOnePlusPlugin @Inject constructor(...)
```

Libre 3 native is `@IntKey(447)`. Driver code stays in `:plugins:dexcom_oneplus` / `:plugins:libre3` `src/main`. No GATT in AIMI `commonMain`. Do not redo this inside Lot L.

### 16. `androidMain` vs `commonMain` ResourceHelper

Bad: moving Autotune’s `ResourceHelper` + `org.json` pattern into AIMI `commonMain`.

Good: Autotune stays `androidMain` with those APIs. AIMI files that need them stay `androidMain` too (T2/T3).

### 17. Format / locale

Bad in commonMain:
```kotlin
String.format(Locale.US, "%.1f", x)
```

Good: `NumberFormat` (`DetermineBasalSMB`) or existing `aimiFmt0` / `aimiFmt1` / `aimiFmt2`.

### 18. Dump `android.util.Log` must die before commonMain

Bad (still in dump: `AimiModelHandler`, `StepService`, `GeminiModelResolver`, …):
```kotlin
Log.e("AIMI", e.message ?: "")
```

Good: `aapsLogger.error(LTag.AIMI, msg)` or leave the file in `androidMain` until the log line is gone.

### 19. Do not restore the 324-file dump in one lot

Bad: copy `_docs/kmp/staging/openAPSAIMI-android-wip/` → `androidMain` in one go (lots A–C already showed this will not compile).

Good: Lot L = a short T1 list below. Compile `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain` with `--no-daemon`.

### 20. iOS targets are not an AIMI product claim

Bad: “commonMain compiled on iosSimulatorArm64, so AIMI runs on iPhone.”

Good: `:plugins:aps` declares `iosArm64` / `iosSimulatorArm64` so shared math can compile. There is no AIMI iOS shell and no enact on iOS.

---

## Dump files that CAN move to commonMain next (T1)

Source: `_docs/kmp/staging/openAPSAIMI-android-wip/` (324 kt).  
Filter: no `android.*`, no `java.io.File`, no `org.json`, no Compose/Activity, not the tick/plugin.  
Do **not** move all ~180 T1-looking files in one lot.

### Lot L (recommended) — about 20 files, no `System.currentTimeMillis`

Rewrite on copy: Metro imports already present on several; add `aimiWallClockMs()` only if a clock slips in; `LTag.AIMI` if they log; explicit imports.

- `GlucoseStatusCalculatorAimi.kt`
- `ISF/DynIsfTrajectoryTuning.kt`
- `IsfSourceTelemetry.kt`
- `MealCorrectionContextResolver.kt`
- `UndeclaredCobEstimator.kt`
- `activity/EffortActivityBelief.kt`
- `activity/ExerciseHyperOverridePolicy.kt`
- `basal/BasalTerminalInvariants.kt`
- `basal/DynamicBasalController.kt`
- `basal/T3cAutodriveBasalBridge.kt`
- `basal/T3cTrajectoryContext.kt`
- `comparison/KpiCalculator.kt`
- `comparison/PerformanceScorer.kt`
- `inflammatory/InflammationAdjuster.kt`
- `pkpd/DiaGovernor.kt`
- `pkpd/PkpdAbsorptionGuard.kt`
- `pkpd/PkpdSmbTailDamping.kt`
- `pkpd/TapPeakGovernor.kt`
- `safety/InsulinLoadGovernor.kt`
- `safety/InsulinStackingStance.kt`
- `smb/SmbDampingUsecase.kt`
- `trajectory/TrajectoryGuard.kt`
- `trajectory/TrajectoryMetricsCalculator.kt`

Skip even if they look “pure”: `keys/AimiStringKey.kt` (`titleResId` + `R`), `orchestration/AimiDetermineBasalTickOrchestrator.kt` (tick), anything with `System.currentTimeMillis` until the clock rewrite is in the same lot.

Later T1 waves (still dump, still not Android types): `physio/pattern/*` math, `recursive/*` without File, `patient/*` without runtime repositories, `release/*`, `scenario/*`, `wcycle/*` adjusters. One wave at a time.

---

## Dump files that MUST stay androidMain (T2 / T3)

Keep in the dump until a **host** lot. Then `src/androidMain`, not `commonMain`.

| Bucket | Examples |
|---|---|
| Plugin + 18k tick | `OpenAPSAIMIPlugin.kt`, `DetermineBasalAIMI2.kt`, `DetermineBasalInvocationCaches.kt`, `orchestration/AimiDetermineBasalTickOrchestrator.kt`, `orchestration/AimiLoopTelemetry.kt` |
| Metro host / workers | `di/*`, `learning/BasalMlTrainerWorker.kt`, Autodrive `*Worker.kt` |
| Compose + Activities | `compose/*`, `advisor/AimiModeSettingsActivity.kt`, `advisor/AimiProfileAdvisorActivity.kt`, `advisor/meal/MealAdvisorActivity.kt`, `advisor/meal/MealAdvisorCameraActivity.kt`, `advisor/auditor/ui/*`, `context/ui/*` |
| SOS / SMS | `sos/*` |
| Health Connect / steps / sensors | `steps/*`, `StepService.kt`, `physio/AIMIHealthConnect*`, `physio/AIMIPhysioWorkersMTR.kt` |
| Files / SAF | `utils/AimiStorageHelper.kt`, `utils/AimiBackupManager.kt`, `tpo/TpoPersistence.kt`, `hormonitor/viewer/HormonitorReader.kt`, `advisor/auditor/AuditorJsonlExport.kt`, `advisor/data/*Reader.kt`, `learning/BasalMlTrainingCoordinator.kt`, `ISF/SensitivityRatioEstimator.kt`, Autodrive data lake / locks |
| TFLite / ONNX / NN | `aimiNeuralNetwork.kt`, `AimiModelHandler.kt`, `ml/*`, `advisor/oref/OrefOnnxScorer.kt` |
| HTTP / Context LLM | `context/ContextLLMClient.kt`, `physio/AIMILLMPhysioAnalyzerMTR.kt`, `physio/thermal/OuraApiThermalClient.kt`, meal vision `*Provider.kt` |
| `org.json` still | `context/ContextManager.kt` (peel JSON first if it ever goes commonMain) |
| Log / R leftovers | files with `android.util.Log` or `import app.aaps.plugins.aps.R` until rewritten |

Layouts stay parked in `_docs/kmp/staging/res-layout-wip/` until AAPT styles/strings exist.

---

## Concerns (why not DONE)

1. **`:plugins:source` merge leftover.** kmp parent is KMP (`androidMain`, Metro, no Hilt). AIMI parent is `com.android.library` + Hilt + One+/Libre3 deps + `src/main`. The merge kept AIMI’s **gradle** and kmp’s **folders**. Sources are in `src/androidMain` while an `android.library` module still expects `src/main`. Do **not** copy this gradle. A host lot should restore the kmp KMP gradle and keep `@IntKey(446)` / `@IntKey(447)`.
2. Dump `AimiStringKey` is not T1 until it uses `TextRef` / `ApsStrings`.
3. Lots A–C copies were parked. `ApsPluginRegistrations` has no AIMI `@IntKey(225)`.
4. Dump still uses `LTag.APS`, `ResourceHelper`, and `android.util.Log` in host files.
5. JSON T1 is split: some files live only in `commonMain` (`TpoModels.kt`), some only in the dump again (`AimiClinicalReportEngine.kt`). Check before copy so you do not duplicate a type.

## Next coding lot

**Lot L — T1 math peel:** copy the ~20 files listed above from the dump into `:plugins:aps` `commonMain`, apply the import rules, compile `:plugins:aps` only. Leave the dump, plugin, tick, and `:plugins:source` gradle for other lots.
