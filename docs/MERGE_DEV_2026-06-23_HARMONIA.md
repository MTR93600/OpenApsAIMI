# Merge `dev` → `codex-aimi-harmonia-simulation-branch` (2026-06-23)

- **Source branch:** local `dev` @ `6436a9556f` (`Dash: sync after pair`)
- **Target branch:** `codex-aimi-harmonia-simulation-branch` @ `05aa9c2fec` (Harmonia + PatientEventMemory trunk fix)
- **Merge-base:** `4030e4827f` ([MERGE_DEV_2026-06-16_NS_SETTINGS.md](MERGE_DEV_2026-06-16_NS_SETTINGS.md))
- **Upstream delta:** 58 commits (wear scenes, running mode, BGI scale, libs API 37, Afrezza wear, NSCv3 PATCH retry, Equil hardening, glucose unit labels)

## Merge conflicts resolved (combine, not theirs-only)

| Area | Files | Resolution |
|------|-------|------------|
| **Versions / SDK** | `Versions.kt`, `app/build.gradle.kts` | `compileSdk = 37` (upstream); `targetSdk = 34` kept for Health Connect |
| **Gradle libs** | `gradle/libs.versions.toml` | `androidx-core 1.19.0` (dev) + `fragment`/`gridlayout` (fork) |
| **Strings** | `core/ui/.../strings.xml` | Fork `reload` + dev `mg/dL` / `mmol/L` / `profile_isf_units_*` |
| **DI / receivers** | `ImplementationModule.kt` | **Both** `WarnColorsImpl` (fork) + `BTReceiver`/`ChargingStateReceiver` (dev) |
| **Running mode** | `RunningModeManagementViewModel.kt` | Dev `profileSet` + fork `withContext(IO)` for loop calls |
| **Localization** | `plugins/aps/.../values-bg-rBG/strings.xml` | Fork autotune log strings + dev Bulgarian `pump_disconnected` |

## Post-merge compile fixes (incomplete combine)

| File | Fix |
|------|-----|
| `app/build.gradle.kts` | Hardcoded `compileSdk = 36` → `Versions.compileSdk` (37) |
| `UiInteractionImpl.kt` | Missing `import app.aaps.core.keys.interfaces.Preferences` |
| `DataReceiver.kt` | Ottai `enqueueInline` — removed stale `context` first arg (dev receivers API) |

## Fork invariants verified intact

| Gate | Status |
|------|--------|
| `:plugins:eversense` in `settings.gradle` | OK |
| `KeepAliveWorker`: `runVacuum = false` | OK |
| `AdaptiveSmoothingPlugin`: `calibratedOrValue` | OK |
| `AimiStorageHelper` / ML model copy in `MainApp` | OK |
| `AndroidManifest` Health Connect + physio permissions | OK |
| Dashboard skin switch (`MainScreen` `dashboardOverview`) | OK |
| `AimiHormonitorStudyExporterMTR` / `patient_story` export | OK (schema 1.4.0 on branch) |
| `DataInbox.drain()` before gate (Dexcom/xDrip/APS export) | OK |
| `OpenAPSAIMIPlugin` in `PluginsListModule` | OK |
| Harmonia fork (`PatientEventMemoryCalculator`, trunk fix) | OK |

## AIMI / upstream parity

| Upstream change | AIMI impact | Action |
|-----------------|-------------|--------|
| Wear scenes / running mode confirmation | Dashboard modes UI only | `RunningModeManagementViewModel` combined |
| BGI/DEV shared vertical scale | Overview graphs | Auto-merged; no AIMI port |
| Glucose unit label resources (`mg/dL`) | Display only | English strings combined |
| `receivers` → `:implementation` | `DataReceiver` worker enqueue API | Fixed Ottai call site |
| Afrezza wear handlers | `DataHandlerMobile` | Dev path merged; AIMI loop unchanged |
| `LoopPlugin` suspend / pump resume fixes | Shared loop stack | Fork AIMI autodrive coalescing preserved |
| Libs API 37 + `androidx.core 1.19` | Build only | `compileSdk` aligned |

**No SMB/AutoISF-specific port required** for this batch beyond shared-layer fixes. `DetermineBasalAIMI2` untouched by upstream (fork-only file).

## AIMI-specific documentation cross-check

| Document | Status after merge |
|----------|-------------------|
| [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) | Applied; device smoke **pending user** |
| [MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md) | Eversense module + DI preserved |
| [AIMI_STORAGE_SECURITY_STATUS.md](AIMI_STORAGE_SECURITY_STATUS.md) | Valid — storage helper unchanged |
| [AIMI_HORMONITOR_STUDY_NOTES_2026-06-06.md](AIMI_HORMONITOR_STUDY_NOTES_2026-06-06.md) | Exporter structure unchanged |
| [aimi-harmonia-implementation.md](aimi-harmonia-implementation.md) | Harmonia paths preserved |
| [AIMI_TRANSIENT_PREFERENCE_OVERLAY.md](AIMI_TRANSIENT_PREFERENCE_OVERLAY.md) | Unaffected |
| [AIMI_REFACTOR_CHECKLIST.md](AIMI_REFACTOR_CHECKLIST.md) | No new upstream AIMI API drift |

## Verification completed (local)

- `:app:assembleFullDebug` — **PASS**
- `:plugins:aps:testFullDebugUnitTest` — PatientEventMemory, PhysiologicalTree, HarmoniaHarmonizer, PhysiologicalPatternPolicy — **PASS**

## Verification pending (device)

Per [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) §4:

- [ ] Dashboard ↔ classic overview skin switch
- [ ] AIMI JSON/CSV writes (with/without shared storage)
- [ ] Hormonitor export on device (`patient_story`)
- [ ] Health Connect thermal grant flow
- [ ] Adaptive Smoothie with linear calibration
- [ ] Eversense native + notification reader paths
