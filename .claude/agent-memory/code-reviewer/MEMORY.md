# Code Reviewer Memory

## Key Patterns Confirmed

- `AapsSpacing` object: `extraSmall`(2), `small`(4), `medium`(8), `large`(12), `extraLarge`(16),
  `xxLarge`(24). Use instead of hardcoded `.dp` literals.
- `clearFocusOnTap` in `app.aaps.core.ui.compose.Modifiers.kt`. Required for screens with text
  fields.
- `ComposablePluginContent` in
  `core/ui/src/commonMain/kotlin/app/aaps/core/ui/compose/ComposablePluginContent.kt`.
- `PluginBase.pluginScope` is protected and lives as long as the process. Work that must stop with
  the plugin goes in a scope the plugin creates in `onStart()` and cancels in `onStop()`. Every
  `pluginScope.launch` is listed in `PluginLifetimeWorkScanTest` (app testFull).
- Previews MUST use `MaterialTheme` wrapper (NOT `AapsTheme` — crashes in preview tool).

## DI Patterns

- **One framework everywhere: Metro.** A view model is registered with
  `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())` + `@ViewModelKey`, and read
  in Compose with `metroViewModel()`.
- **ComposeContent constructor**: Always constructed manually in the plugin, receives only
  non-ViewModel deps (e.g., `protectionCheck`, `blePreCheck`) as constructor params.

## Recurring Bugs Across Pump Compose Migrations

- `BlePreCheckHost` and the wizard screen render at the same time, so the wizard shows before the
  BLE check is done. Gate the wizard on a separate "BLE ready" state.
- `SharedFlow` event branches left empty (`// handled inline`) when they are NOT handled anywhere.
  Check every branch really handles its event.
- Public `val rh: ResourceHelper` on ViewModels — should always be `private val`.
- `canGoBack` as a plain computed property reading `StateFlow.value` is not reactive in Compose.
  Derive a `StateFlow` instead.
- Step composables must read ViewModel data from `StateFlow`, not from plain function calls.
- Step counts (`totalSteps`) must come from the workflow's step list, not from a number written
  next to it: shared steps reused across workflows made the two disagree.
- When porting a confirm step, check every `insertTherapyEvent` call for a duplicate event.
- A "Finish" button must stay disabled until the command it waits for has been sent and succeeded.
  Easy to forget when porting from XML, where the button was only made transparent.
- A plain `var` written from another thread or coroutine needs `@Volatile`, or a `StateFlow`.
- Check string values, not just keys: stray quotes and full-width punctuation slip in.

## AIMI Advisor: Tuning Context / Profile Advisor sub-lot (2026-09-13, verified real)

- `AimiAdvisorService.calculateMetrics` (androidMain) has hardcoded fallback metrics
  (`tir70_180=0.65`, `timeBelow70=0.05`, `timeAbove180=0.30`, etc.) used whenever the optional
  `tirCalculator`/`tddCalculator`/`persistenceLayer` ctor params are null — confirmed real, not
  hypothetical. `TuningContextEngine.computePlan` and `PkpdAdvisor.analysePkpd` both key their
  tier/direction decisions off these exact fields, so a missing `tirCalculator` silently produces
  fake-but-plausible tuning advice. `TirCalculator` is already `@ContributesBinding(AppScope::class)`
  bound in `:implementation` (`TirCalculatorImpl`) and already constructor-injected elsewhere in this
  same plugin (`DetermineBasalAIMI2`), so adding it as a new `OpenAPSAIMIPlugin` ctor param is NOT a
  new inter-module dependency and resolves fine via DI.
- Gotcha found: fixing the fallback for ONE `AimiAdvisorService(...)` construction site does not fix
  it for others. `OpenAPSAIMIPlugin.kt` builds `AimiAdvisorService` inline in at least 2 places
  (`AimiProfileAdvisor` compose screen, and `aimiComposePkpdSetupItem`'s `loadPkpdRecommendations`);
  the latter still omits `tirCalculator` after this sub-lot, so its PKPD recommendations still run on
  fake TIR. Check ALL construction sites when reviewing a claimed "wire in the real calculator" fix,
  not just the one under review.
- `TuningContextApplySupport.tryExportSettings(importExportPrefs: ImportExportPrefs,
  exportPasswordDataStore: ExportPasswordDataStore): TuningExportStatus` — no `context` param, confirmed
  against source (`plugins/aps/.../advisor/tuning/TuningContextApplySupport.kt`).
- `AimiTuningContext` enum has 5 values but `MIXED_BALANCE` is intentionally not a UI chip (KDoc:
  "Resolved from AUTO_BALANCE only ... Not shown as a separate UI chip") — the original staged
  Activity also only ever rendered 4 chips. Don't flag this as missing UI; it's by design, confirmed
  in both old and new code.
- Real, confirmed regression pattern to watch for in Compose ports of this advisor family: the
  original `AimiProfileAdvisorActivity` wrapped its `generateReport()` load in `lifecycleScope.launch
  { try { ... } catch (t: Throwable) { show visible error text, special-cased OOM } }`. The Compose
  port's `LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { advisorService.generateReport() } }`
  had ZERO try/catch — an exception crashes the LaunchedEffect's coroutine (likely app crash) or at
  best leaves the `CircularProgressIndicator` spinning forever with no user-visible error, unlike both
  the original Activity and the sibling `AimiMealAdvisorScreen` (which does wrap its async loads in
  try/catch). Always diff the original's error handling explicitly, not just its happy path, when
  reviewing "loaded synchronously -> now async" claims.

## Architecture Notes

- `WizardStepLayout`, `StepProgressIndicator`, `WizardButton` in
  `core/ui/src/commonMain/kotlin/app/aaps/core/ui/compose/pump/WizardStepLayout.kt` — shared wizard
  chrome components.
- `BlePreCheckHost` in `core/ui/src/androidMain/kotlin/app/aaps/core/ui/compose/pump/BlePreCheckHost.kt`
  — async, renders the wizard at the same time unless guarded.

## AIMI Advisor Compose Migrations (OpenApsAIMI fork, kmp-aimi-migration-study branch)

- Series of small live-control screens ported from `_docs/kmp/staging/openAPSAIMI-android-wip/advisor/`
  (deleted after port) into `plugins/aps/.../openAPSAIMI/` (now in `commonMain`, under `context/ui` and `advisor/*/ui`):
  `AimiContextScreen`, `AimiMealAdvisorScreen`, `AimiModeSettingsScreen`. All wired the same way in
  `OpenAPSAIMIPlugin.kt`: `ApsIntentKey.<Name>.withCompose(ComposeScreenContent { onBack -> ... })`,
  manually constructed with plain constructor params (preferences, persistenceLayer, aapsLogger,
  onBack) — no ViewModel/Metro DI used for these (unlike pump wizards).
- `AimiModeSettingsScreen.kt` (reviewed 2026-09-13, clean): writes a `TE.Type.NOTE` with hardcoded
  English note text ("Lunch"/"Dinner"/"Breakfast"/"High Carb") that `therapy.kt`'s
  `findActiveLunchEvents`/etc. match case-insensitively by substring to drive real dosing
  (`DetermineBasalAIMI2`). The port correctly kept the note text as a raw enum field
  (`AimiModeType.noteText`), separate from the localized `tabLabelRes` used only for UI display —
  do NOT let these merge in a future edit, or non-English UI would leak into the dosing-matched note.
  All 10 preference keys (Lunch/Dinner/BF/HighCarb × Prebolus/Prebolus2/Factor/interval) correctly
  mapped per mode, including the odd-one-out `DoubleKey.OApsAIMIHCFactor` naming. Duration correctly
  kept on the original `getSharedPreferences("aimi_mode_activity", ...)` file/keys rather than
  promoted to an `IntKey` — this was flagged in review instructions as deliberate, not an oversight.
- Recurring minor gap across all 3 of these advisor screens (not just this one): no `@Preview`
  anywhere, and `Card(modifier = ...)` used without `CardDefaults.cardColors(containerColor =
  MaterialTheme.colorScheme.surfaceContainer)` in most call sites (one card in
  `AimiMealAdvisorScreen` does set colors). Worth a follow-up pass across the whole advisor family
  rather than fixing piecemeal per file.

## See Also

- Earlier migration reviews (NSClient, Tidepool, Wear, SMS, Preferences, EOPatch2): see conversation
  history from 2026-03-01 and 2026-03-02.
