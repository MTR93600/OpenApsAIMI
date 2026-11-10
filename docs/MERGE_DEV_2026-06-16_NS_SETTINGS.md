# Merge `dev` → `dev_OAPSAIMI` — NS settings / insulin path / wizard unify (2026-06-16)

- **Source branch:** local `dev` @ `4030e4827f` (`Merge pull request #4867 from nightscout/ns_settings`)
- **Target branch:** `dev_OAPSAIMI` @ `8a1f84c4c2` (meal-capture / physio pattern fixes)
- **Prior fork merge-base on this line:** `d7208d8837` ([MERGE_DEV_2026-06-16.md](MERGE_DEV_2026-06-16.md))
- **Goal:** integrate upstream NS settings sync, insulin general execution path, wizard unify, wear-mobile cleanup, client scenes — without regression on AIMI, Adaptive Smoothie, dashboard skin switching, ML/physio storage, Hormonitor study structure, or Eversense-native paths.

## Upstream themes (4030e4827f batch)

| Theme | Representative commits |
|-------|------------------------|
| NS settings sync | `4030e4827f`, `_docs/PLUGIN_SELECTION_KEY_SYNC.md` |
| Insulin general execution path | `e9a18da813`, `60cd7e8de3`, `5652addc91`, `5e38034a1b` |
| Wizard unify + wear cleanup | `58833e6109`, `048a2c815c`, `7ec8169107` |
| Client scenes / insulin / TBR | `5c50a2f4f7`, `b367577a8e`, `69745c183b` |
| Delete `ConfigExportImport` | Running-config via `SyncSpec` / plugin keys |

## Merge conflicts resolved (combine, not theirs-only)

| Area | Files | Resolution |
|------|-------|------------|
| **Dashboard skin** | `MainScreen.kt`, `ComposeMainActivity.kt` | Fork `dashboardOverview` embedding + upstream `activeSceneState` / scene expiry |
| **Startup / ML** | `MainApp.kt` | `AimiStorageHelper`, ML model copy, `maintainDatabaseIfDue()` (no auto VACUUM), `AutomationRuntime` |
| **DI** | `PluginsListModule.kt` | AIMI + Eversense + AdaptiveSmoothing; NS v1 removed; NS v3 only |
| **Keys** | `BooleanKey.kt` | All AIMI/TPO/Eversense/dashboard keys + upstream NS sync keys |
| **Loop** | `LoopPlugin.kt`, test | Upstream insulin mutex + fork AIMI autodrive coalescing |
| **NS AIMI context** | `NsIncomingDataProcessor.kt` | `ContextManager` inject preserved in `processTreatments()` |
| **Sync migration** | nsclientV1 deleted; workers under `nsclientV3/` | AIMI context path on v3 processor |
| **Prefs / UI** | Adaptive* preferences, `PluginPreferencesScreen`, `AllPreferencesScreen`, strings | Combined fork AIMI compose prefs + upstream visibility |
| **Wear / events** | `EventData.kt`, `DataHandlerMobile.kt` | Upstream `ConfirmAction` lines API; fork wear paths kept |

## Post-merge compile fixes (fork-specific resolution gaps)

These were **not** conflict markers but incomplete combines caught by `:app:assembleFullDebug`:

| File | Fix |
|------|-----|
| `BooleanKey.kt` | Removed deprecated `WizardCalculationVisible` / `WizardCorrectionPercent` (upstream wizard unify deleted them; English strings absent) |
| `Overview.kt` | Dropped `ConfigExportImport` super-interface (deleted upstream) |
| `OverviewPlugin.kt`, `OpenAPSAIMIPlugin.kt` | Removed `configuration()` / `applyConfiguration()` overrides |
| `MainScreen.kt` | Added missing `ManageSheetState` import |
| `RunningModeScreen.kt` | Removed stale `PumpSuspendedResumeSection` + `showOkCancel` fork fragment; aligned `PendingRunningModeAction` arity |
| `DashboardModesActivity.kt`, `OverviewFragment.kt` | `automation.userEvents()` → `automation.events.value` (+ suspend `canRun()`) |
| `NotificationWithAction.kt`, `NSClientFragment.kt` | `activeNsClient` → `firstActiveSync as? NsClient` / `activeSyncs` |
| `DataHandlerMobile.kt` | Afrezza `ConfirmAction` → new constructor (`title`, `message`, `lines`, `deferConfirm`; no `insulin` param) |

## Fork invariants verified intact

| Gate | Status |
|------|--------|
| `:plugins:eversense` in `settings.gradle` | OK |
| `KeepAliveWorker`: `runVacuum = false` | OK |
| `AdaptiveSmoothingPlugin`: `calibratedOrValue` | OK |
| `AimiStorageHelper` / ML JSON-CSV paths in `MainApp` | OK |
| `AndroidManifest` Health Connect + physio permissions | OK |
| Dashboard skin switch (`SkinProvider`, `showHybridDashboard`) | OK |
| `AimiHormonitorStudyExporterMTR` / schema `1.2.0` `patient_story` | OK |
| Meal-capture chain (`PhysiologicalPatternPolicy`, `DetermineBasalAIMI2` shadow cap) | OK |
| `NsIncomingDataProcessor` → `ContextManager` on treatments | OK |

## AIMI / upstream parity check

Upstream batch refactors **shared infrastructure** (insulin execution, wizard/bolus executor, NS settings sync, `ConfigExportImport` removal). **No direct edits** to `DetermineBasalAIMI2` logic beyond pre-merge fork state.

| Upstream change | AIMI impact | Action |
|-----------------|-------------|--------|
| Insulin general execution path | AIMI uses same `LoopPlugin` / pump stack | Verified combine in `LoopPlugin`; no AIMI-specific port required |
| Wizard / batch bolus executor | Wear + manual bolus only | `DataHandlerMobile` Afrezza confirm aligned |
| `ConfigExportImport` removal | `OpenAPSAIMIPlugin` had stale overrides | Removed overrides; settings sync via `SyncSpec` / keys |
| NS v1 removal | AIMI context on NS treatments | Preserved on `NsIncomingDataProcessor` (v3) |
| Automation `userEvents()` → `events` StateFlow | Dashboard modes + overview buttons | Updated fork call sites |

**No SMB/AutoISF delta porting required** for this batch beyond shared-layer fixes above. Monitor upstream insulin path on future merges if AIMI adds custom bolus/SMB execution hooks.

## AIMI-specific documentation cross-check

| Document | Status after merge |
|----------|-------------------|
| [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) | Applied; §4 device smoke **pending user** |
| [MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md) | Eversense module + DI preserved; no CAPTCG path in upstream delta |
| [AIMI_STORAGE_SECURITY_STATUS.md](AIMI_STORAGE_SECURITY_STATUS.md) | Valid — storage helper unchanged |
| [AIMI_HORMONITOR_STUDY_NOTES_2026-06-06.md](AIMI_HORMONITOR_STUDY_NOTES_2026-06-06.md) | Exporter structure unchanged |
| [AIMI_PHYSIOLOGICAL_PATTERN_CATALOG.md](AIMI_PHYSIOLOGICAL_PATTERN_CATALOG.md) | Meal-priority caps preserved |
| [AIMI_MEAL_ABSORPTION_PHASE.md](AIMI_MEAL_ABSORPTION_PHASE.md) | Hysteresis hold ticks (10) preserved |
| [AIMI_TRANSIENT_PREFERENCE_OVERLAY.md](AIMI_TRANSIENT_PREFERENCE_OVERLAY.md) | Unaffected by merge diff |
| [AIMI_REFACTOR_CHECKLIST.md](AIMI_REFACTOR_CHECKLIST.md) | No new upstream AIMI API drift |

## Verification completed (local)

- `:app:assembleFullDebug` — **PASS**
- `:plugins:aps:testFullDebugUnitTest` — `PhysiologicalPatternPolicyTest`, `BasalDecisionEngineTest` — **PASS**

## Verification still pending (device / runtime)

Per [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) §4:

- [ ] Dashboard ↔ classic overview skin switch
- [ ] AIMI JSON/CSV writes with and without shared-storage access
- [ ] Hormonitor export on device (`patient_story` block)
- [ ] Health Connect thermal grant flow
- [ ] Adaptive Smoothie with linear calibration (`calibratedOrValue`)
- [ ] NS v3 sync + AIMI context from treatments
- [ ] Wear wizard / bolus confirm lines on watch
- [ ] Eversense native CGM smoke (if used)

## Merge commit

Merge is **staged and ready**; run `git commit` to conclude (not committed automatically).

Suggested message:

```
Merge dev @ 4030e4827f (NS settings, insulin path, wizard unify)

Combine fork AIMI/dashboard/Eversense/physio paths with upstream NS sync,
insulin execution refactor, and ConfigExportImport removal. Post-merge
compile fixes for automation StateFlow, NsClient access, ConfirmAction API.
```
