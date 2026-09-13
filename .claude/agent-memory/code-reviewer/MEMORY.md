# Code Reviewer Memory

## Key Patterns Confirmed

- `AapsSpacing` object: `extraSmall`(2), `small`(4), `medium`(8), `large`(12), `extraLarge`(16),
  `xxLarge`(24). Use instead of hardcoded `.dp` literals.
- `clearFocusOnTap` in `app.aaps.core.ui.compose.Modifiers.kt`. Required for screens with text
  fields.
- `ComposablePluginContent` in
  `core/ui/src/main/kotlin/app/aaps/core/ui/compose/ComposablePluginContent.kt`.
- `PluginBase.scope` is private; plugins must declare their own `CoroutineScope`.
- Previews MUST use `MaterialTheme` wrapper (NOT `AapsTheme` — crashes in preview tool).

## DI Patterns

- **One framework everywhere: Metro.** A view model is registered with
  `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())` + `@ViewModelKey`, and read
  in Compose with `metroViewModel()`.
- `ComposablePluginContent` receives `viewModelFactory` and calls
  `ViewModelProvider(viewModelStoreOwner, viewModelFactory)[...]`. Instantiating via `remember {}`
  is a latent lifecycle bug (viewModelScope never cancelled correctly).
- **ComposeContent constructor**: Always constructed manually in the plugin, receives only
  non-ViewModel deps (e.g., `protectionCheck`, `blePreCheck`) as constructor params.
  non-ViewModel deps (e.g., `protectionCheck`, `blePreCheck`) as constructor params.

## Recurring Bugs Across Pump Compose Migrations

- `BlePreCheckHost` + wizard screen render simultaneously — wizard shows before BLE check completes.
  Need a separate `isCheckingBle` state to gate wizard rendering. (EOPatch2, Equil) — STILL OPEN in
  Equil.
- `SharedFlow` event branches left empty (`// handled inline`) when they are actually NOT handled.
  (Equil: `ShowMessage` in `EquilComposeContent` swallows unpair errors)
- Public `val rh: ResourceHelper` on ViewModels — should always be `private val`. (EOPatch2,
  Equil) — FIXED in Equil.
- `canGoBack` implemented as a plain Kotlin computed property reading `StateFlow.value` instead of
  a derived `StateFlow` — not reactive in Compose. (Equil) — FIXED.
- Step composables accessing ViewModel data via plain function calls instead of `StateFlow`. (
  Equil) — FIXED.
- Step count (`totalSteps`) mismatch when shared steps (AIR, CONFIRM) are reused across workflows
  without updating the workflow's declared `totalSteps`. (Equil: CHANGE_INSULIN declares 4 but
  runs 6 steps) — FIXED (count correct), but comment in EquilWizardStep.kt line 15 not updated.
- Duplicate therapy event insertion in activation confirm step — check all `insertTherapyEvent`
  calls when porting confirm logic. (Equil: double CANNULA_CHANGE event) — RESOLVED, not duplicated.
- Air removal step: "Finish" button must be disabled until the removal command has been sent and
  succeeded. Easy to forget when porting from XML (button was initially disabled via alpha). (
  Equil) — FIXED.
- Callback.run() in commandQueue executes on background HandlerThread — all MutableStateFlow.value
  assignments from callbacks are safe (StateFlow is thread-safe), but plain `var` fields (
  autoFillActive,
  fillStepCount) accessed from callbacks are NOT safe without @Volatile.
- Empty password allowed through SerialNumberStep — `isPasswordValid = password.isEmpty() || ...`
  lets user pair with no password, which is stored and used for future unpair.
- Resource strings with embedded stray characters: equil_install has trailing `"`,
  equil_unbind_content
  has full-width `！`. Always check string values not just keys.
- `GIF_MAX_HEIGHT = 300.dp` duplicated in 4 step files — WizardGifImage.kt was planned but not
  created.

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

- `WizardGifImage.kt` / `WizardImage` in `core/ui/compose/pump/` — shared GIF/image wrapper for
  wizards. Step composables should use this instead of copy-pasting `GlideImage` boilerplate.
- `WizardStepLayout`, `StepProgressIndicator`, `WizardButton` in `core/ui/compose/pump/` — shared
  wizard chrome components.
- `BlePreCheckHost` in `core/ui/compose/pump/BlePreCheckHost.kt` — async, renders wizard at same
  time unless guarded.

## AIMI Advisor Compose Migrations (OpenApsAIMI fork, kmp-aimi-migration-study branch)

- Series of small live-control screens ported from `_docs/kmp/staging/openAPSAIMI-android-wip/advisor/`
  (deleted after port) into `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`:
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

- `equil-migration.md` — detailed Equil Compose migration review (2026-03-09)
- Earlier migration reviews (NSClient, Tidepool, Wear, SMS, Preferences, EOPatch2): see conversation
  history from 2026-03-01 and 2026-03-02.
