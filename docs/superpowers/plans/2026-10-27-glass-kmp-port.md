# Glass skin: port from dev_OAPSAIMI to the KMP branch

Goal: the Glass home and everything it opens behave on Android as on `dev_OAPSAIMI`
(@ `ac78a118836`), written in `commonMain` so iOS gets the same screens.

Source of truth: read the old code with `git show dev_OAPSAIMI:<path>`. Never check out that branch.

## What exists already (do not duplicate)

- Skin choice: `StringKey.GeneralDashboardSkin` (`dashboard_home_skin`), `DashboardHomeSelection`
  (`ui/.../compose/dashboard/`). Host: `ui/.../compose/main/OverviewScreen.kt` passes
  `dashboardV2Content` to `MainScreen`.
- A simplified Glass home: `GlassDashboardHome`, `GlassDashboardScreen`, `GlassOverviewMetricsCard`,
  `GlassAimiRuntimeCards`, `GlassCgmLinkStatus`. It is NOT the DEV design. It is replaced by the
  DEV design in phase 3. `GlassAimiRuntimeCards` content moves to the loop dashboard (phase 5),
  where DEV shows it.
- AIMI data: `AimiGlassDashboardData` (core/interfaces) fed by `AimiGlassDashboardSnapshotFeed`
  (plugins/aps). `:ui` does not depend on `:plugins:aps`; hosts pass the Flow in.
- Shared dashboard parts in `core/ui/.../compose/dashboard/` (graph card, hero, ring, notifications
  list, quick actions...). Reuse them before writing new code.

## Conventions on this branch

- Strings: English only, in the module's `src/androidMain/res/values/strings.xml`; in common code
  `stringResource(UiStrings.x)` / `CoreUiStrings.x` from `app.aaps.core.ui.compose.stringResource`.
  No `R.string`, no plurals in common code. Hardcoded English in DEV code becomes a string resource.
- Icons: `ImageVector` in `core/ui/.../compose/icons/`. DEV `ic_glyco_*.xml` drawables are converted
  to ImageVectors. No `painterResource`.
- Navigation: screens call `onNavigate(NavigationRequest)`; routes live in
  `appshell/.../navigation/AppRoute.kt` and `AppNavGraph.kt`; protection checks in `ElementNavigator`.
  The DEV Intent round trip (`openComposeMainAtRoute`) is not ported.
- ViewModels: `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>()) @ViewModelKey
  @Inject`, taken with `metroViewModel()`. No Hilt.
- Charts: plain Compose `Canvas`; text with `TextMeasurer`/`drawText`; time with kotlinx-datetime.
  No `android.graphics`, no `java.*`.
- Platform-only code (BackHandler, orientation): `expect`/`actual` or a parameter, never a silent no-op.
- Tests: pure logic in `commonTest` (kotlin.test); Compose UI tests in `androidHostTest`
  (Robolectric). Port the DEV tests listed below.

## Phases (one at a time, each compiled for Android and iOS and tested before the next)

1. **Foundation** (`core/ui` + `ui` commonMain): `GlassColors`, `GlassCard`, `GlassSectionCard`,
   `GlassScreenKit` (`isGlassDarkMode`, `GlassScreenBackground`), `GlassContainer`/`GlassPill`/
   `BottomMetricPill`, `GlassRangeSelector`, models (`GlassChartModels`, `GlassOverviewModels` with
   enums instead of ARGB ints and drawable ids, `GlassLoopDashboardModels`), `GlassHeroCommands`
   (as navigation callbacks), `GlassPersonalization` + keys `GlassSelectedPills`,
   `GlassSelectedTools`, `GlassTopGridPillsMigrated`, `DashboardV2ToolAction` and
   `DashboardV2StatusValueResolver`, the five `ic_glyco_*` icons. Tests: `GlassPersonalizationTest`,
   `DashboardV2ToolActionTest`, `DashboardV2StatusValueResolverTest`.
2. **Charts**: `GlassChartComponents` (`BgChartCard`, `IobChartCard`) and `buildGlassChartState` fed
   by `GraphViewModel` flows. Test: `GlassChartStateMapperTest`.
3. **Home**: `StatusAgoraCard`, `GlassNotifications`, the Glass root (DEV
   `GlassOverviewComposeEmbedded`) replacing the simplified home, Tools overlay
   (`DashboardV2ToolsScreen`) and `GlassPersonalizeScreen`. Tests: `GlassUiStateBuilderTest`,
   `GlassOverviewComposeEmbeddedKtTest`.
4. **Bottom bar**: `GlassNavigationBar` in `MainScreen` when the skin is Glass; quick-launch toolbar
   hidden. Test: `GlassNavigationTabTest`.
5. **Destinations**: routes and screens for loop dashboard (with its ViewModel, AIMI data from the
   snapshot feed), loop detail, pump, battery, cannula, sensor quality, sensor insert, insulin,
   target, basal. Test: `GlassLoopDashboardViewModelTest`.
6. **Restyle**: Stats (+ `GlucosePentagonCompose`, `TddCyclePatternCompose`, `LoadingSection`) and
   Treatments with its 8 tabs, unconditional as on DEV.
7. **Check**: full CI command, `iosSimulatorArm64Test`, Xcode app link, and an independent review
   against `dev_OAPSAIMI` file by file.

## State (2026-10-28)

Phases 1 to 7 are written, compiled and tested (full CI command, `iosSimulatorArm64Test` on all
modules, Xcode app build for the simulator). Nothing is committed. **Not yet checked on a device:**
the user must confirm the look and the behaviour in the app.

Also done with the port:
- The chart draws calibrated glucose, as DEV did: `PrepareGraphDataRunner` goes through
  `GlucoseCorrection`. Before, it drew the raw stored value.
- The AUDITOR_REPORT tool tile opens the report on Android and iOS through `AimiAuditorReport`
  (one shared implementation in plugins/aps commonMain).
- `AimiGlassDashboardSnapshotFeed.observe()` runs on `aapsIoDispatcher`; it read the database on the
  main thread every minute.

Deliberate differences from DEV (check these on the phone, they are not bugs):
- A missing value shows "--", not 0. A forecast older than 9 min is hidden.
- "Open AIMI context" is shown only when that screen exists.
- Translatable formats instead of joined strings: reservoir "U" (DEV "IE"), IOB header with the
  phone's decimal separator, ages as "2d5h".
- Pump status line uses Glass amber and red instead of pure yellow; "forever" text not ported.
- Treatment screens are stricter: SMB-in-progress blocks OK, record-only banner, client role checked,
  BOLUS protection also for sensor insert, Activate hidden while no preset is visible.
- On an unpaired client the pills that open a treatment do nothing.
- Stats keep the upstream four-tab pager, in Glass colours, instead of DEV's long list.
- Cannula screen opens with "site change" preselected (DEV: nothing).
- Desktop shows the four AIMI tool tiles disabled (no AIMI there).
- iOS: steps today come from the database only (no HealthKit total yet); tapping the iOS auditor
  notification opens the AIMI settings, not the report.

Not ported: the View-based host on DEV (`DashboardShellController`, `OverviewViewModel`,
`DashboardFragment`, `AimiDashboardComposeRootView`, `DashboardOverviewHost`, skin classes). Their
job is done by `MainScreen` + `OverviewScreen` on this branch. Phase 7 checks that no behaviour was
lost with them.
