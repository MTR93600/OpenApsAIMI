# Glass Bottom Navigation Bar — Design Spec

**Status:** Ready for self-review then user review.

## Goal

Replace the Glass dashboard's bottom navigation with a Glass-specific 6-item bar — Treatments, Profiles,
Bolus, Pump, Scenarios, Management — matching the reference mockup, without changing the bottom nav used by
`OVERVIEW`/`DASHBOARD_V1` (which currently share the same bar Glass uses today).

## Background

On-device feedback (2026-09-11): the Glass dashboard's current bottom bar only shows 3 items (Treatments,
Scenarios, Management); the reference mockup shows 6. Confirmed via `AskUserQuestion`: the user explicitly
approved touching `MainScreen.kt` to add this.

## Current state (researched, not guessed)

- `ui/src/main/kotlin/app/aaps/ui/compose/main/MainNavigationBar.kt` is a **fixed imperative sequence** of
  up to 8 `NavigationBarItem` calls (no enum, no loop) — used today by `OVERVIEW`, `DASHBOARD_V1`, **and**
  `GLASS` alike. Its Treatments/Scenarios/Management items call plain lambda params
  (`onTreatmentClick`/`onAutomationClick`/`onManageClick`) that the caller (`MainScreen.kt`) wires to local
  bottom-sheet state, not to compose-nav routes.
- `ui/src/main/kotlin/app/aaps/ui/compose/main/DashboardV2NavigationBar.kt` is a **different**,
  already-committed bar used only by `DASHBOARD_V2`: an enum (`DashboardV2NavigationTab`) with a
  `fixedOrder: List<...>`, rendered via one `NavigationBar { fixedOrder.forEach { ... } }` loop, with private
  `icon()`/`labelRes()` `when`-extensions on the enum. This is the STRUCTURAL PATTERN to copy for Glass — not
  its actual 5-item, V2-specific content.
- The swap point is `MainScreen.kt` lines ~435-488, inside one `AnimatedVisibility` block:
  ```kotlin
  if (dashboardTools != null) {
      DashboardV2NavigationBar(...)
  } else MainNavigationBar(
      onManageClick = { manageSheetState.show() },
      onTreatmentClick = { treatmentViewModel.refreshState(); showTreatmentSheet = true },
      onAutomationClick = { scenesViewModel.refreshState(); showAutomationSheet = true },
      onNavigate = onNavigate,
      ...
  )
  ```
  `dashboardTools` is non-null only for `DASHBOARD_V2` (`ComposeMainActivity.kt:904`). Glass sets
  `dashboardOverview` but leaves `dashboardTools = null`, so Glass falls into the `else` branch today,
  sharing `MainNavigationBar` with `OVERVIEW`/`DASHBOARD_V1`.
- `MainScreen(...)` is invoked once, from `ComposeMainActivity.kt:765`, inside
  `key(dashboardHomeVariant, generalSkin) { ... }` — `dashboardHomeVariant` (a `DashboardHomeVariant` enum:
  `OVERVIEW, DASHBOARD_V1, DASHBOARD_V2, GLASS`) is already resolved and in scope at that exact call site
  (`ComposeMainActivity.kt:677`), the same place `dashboardTools = if (dashboardHomeVariant ==
  DashboardHomeVariant.DASHBOARD_V2) {...} else null` is already computed (line 904) — so adding one more
  boolean derived the same way is a one-line, zero-risk addition.
- Routing options confirmed to already exist and work:
  - **Profiles**: `AppRoute.Profile` (`app/.../compose/navigation/AppRoute.kt:11-14`), reached today via
    `NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)` → `ComposeMainActivity.handleNavigationRequest`
    → `navigateProtected(...)` → `navigateToElement` → `navController.navigate(AppRoute.Profile.createRoute(mode))`.
  - **Bolus**: two existing options — `ElementType.INSULIN` (opens `AppRoute.InsulinDialog`, a quick
    manual-entry dialog, no calculator — this is what the Treatments sheet's own "Insulin" tile already
    uses) and `ElementType.BOLUS_WIZARD` (opens `AppRoute.WizardDialog`, the full bolus calculator). Both go
    through the same `NavigationRequest.Element(...)` → `navigateProtected` path as Profiles.
  - **Pump**: `AppRoute.GlassPumpDetail` (route string `"glass_pump_detail"`) already exists and is already
    wired into `AppNavGraph.kt:574-587` (`GlassPumpDetailScreen`, added earlier this session for the
    top-grid Pump pill). It is reached today via `uiInteraction.openComposeMainAtRoute(context,
    "glass_pump_detail")` (used from the legacy-Fragment-hosted `DashboardShellController`, a different
    Activity boundary than `MainScreen`) — not through `NavigationRequest`, since `NavigationRequest` has no
    raw-route variant today (`core/ui/.../navigation/NavigationRequest.kt`: only `Element`, `QuickWizard`,
    `Plugin`, `PluginPreferences`).
  - **Treatments / Scenarios / Management**: already working via the existing bottom-sheet lambdas
    (`showTreatmentSheet`/`showAutomationSheet`/`manageSheetState.show()`) that `MainScreen.kt` already owns
    as local state — a Glass-specific bar just needs to call the same lambdas, unmodified.

## Decisions

- **New file** `ui/src/main/kotlin/app/aaps/ui/compose/main/GlassNavigationBar.kt`, mirroring
  `DashboardV2NavigationBar.kt`'s pattern exactly: a `GlassNavigationTab` enum (`TREATMENTS, PROFILES, BOLUS,
  PUMP, SCENARIOS, MANAGEMENT`) with a `fixedOrder` list, one `NavigationBar { fixedOrder.forEach { ... } }`
  loop, and private `icon()`/`labelRes()` `when`-extensions. Unlike `DashboardV2NavigationTab` (whose tabs
  are homogeneous — each either a local tab-switch or an `ElementType`), Glass's 6 tabs need heterogeneous
  dispatch (2 bottom-sheet triggers, 1 raw-route navigate, 3 `ElementType`-based navigates via
  `NavigationRequest`) — so `GlassNavigationBar` takes explicit per-tab callback params (`onTreatmentClick:
  () -> Unit`, `onScenariosClick: () -> Unit`, `onManagementClick: () -> Unit`, `onNavigate: (NavigationRequest)
  -> Unit`), the same shape `MainNavigationBar` already uses for its own equivalent items — not a single
  generic `onTabClick(tab)` like V2's (V2's tabs are simple enough for one generic dispatcher; Glass's are
  not).
- **New `NavigationRequest.Route(val route: String)` sealed subclass** (`core/ui/.../NavigationRequest.kt`),
  handled in `ComposeMainActivity.handleNavigationRequest` as `is NavigationRequest.Route ->
  navController.navigate(request.route)`. This lets the Pump tab reuse the SAME `onNavigate` callback
  `MainScreen` already threads everywhere else, instead of inventing a second, parallel navigation
  mechanism just for one tab. No new protection check — the existing `openComposeMainAtRoute` call site for
  this exact route (`DashboardShellController`'s `GlassHeroCommands.openPump()`) is also unprotected today,
  so this is not a new gap, just the same already-accepted behavior reached a second way.
- **Bolus tab → `ElementType.BOLUS_WIZARD`** (the full calculator), not `ElementType.INSULIN` (the quick
  manual-entry dialog already used by the Treatments sheet's own "Insulin" tile). Rationale: a standalone
  "Bolus" bottom-nav action most naturally means "calculate and give a bolus," matching how other AID/pump
  apps use a dedicated Bolus button; the quick manual-entry dialog stays reachable from the Treatments sheet
  as it is today. This is a judgment call, not a hard requirement — easy to flip to `ElementType.INSULIN` by
  changing one line if the user prefers the quick-entry dialog instead.
- **Wiring in `MainScreen.kt`**: add one new parameter, `isGlassSkin: Boolean = false`, and change the
  bottom-bar branch from `if (dashboardTools != null) {...} else MainNavigationBar(...)` to `if
  (dashboardTools != null) {...} else if (isGlassSkin) { GlassNavigationBar(...) } else
  MainNavigationBar(...)`. At the call site (`ComposeMainActivity.kt:765`), pass `isGlassSkin =
  (dashboardHomeVariant == DashboardHomeVariant.GLASS)` — computed the same way `dashboardTools` already is,
  just a plain boolean instead of a composable slot since the nav bar (unlike Tools' full-screen content)
  needs no external content injection, only local dispatch.
- **`OVERVIEW`/`DASHBOARD_V1` are untouched**: they keep falling into the final `else MainNavigationBar(...)`
  branch exactly as today.

## Non-goals / deferred

- No change to `MainNavigationBar.kt` or `DashboardV2NavigationBar.kt` themselves — both stay exactly as they
  are, serving their existing skins.
- No change to what happens inside the Treatments/Scenarios/Management bottom sheets, the Profile screen, the
  Bolus wizard, or `GlassPumpDetailScreen` — this chantier only adds a new way to reach them, not new
  behavior inside them.
- No badges/counts on the new bar's items (V1's `MainNavigationBar` shows an automation badge count; the
  mockup for Glass's 6-item bar does not show badges) — can be added later if requested.

## Icons and labels (resolved during self-review)

4 of the 6 icons already have an established, reused-not-reinvented match, confirmed by reading the actual
source:
- **Treatments** → `Icons.Default.Fastfood` (`MainNavigationBar.kt:89`, same icon Glass would otherwise
  duplicate) with label `stringResource(CoreUiR.string.treatments)` (existing shared string, no new resource
  needed).
- **Scenarios** → `IcAutomation` (`MainNavigationBar.kt:118`) with label
  `stringResource(CoreUiR.string.scenes)` (existing shared string).
- **Management** → `Icons.Default.ManageAccounts` (`MainNavigationBar.kt:135`) with label
  `stringResource(CoreUiR.string.manage)` (existing shared string).
- **Bolus** → `Icons.Default.Medication` (already used for `DashboardV2NavigationTab.BOLUS`,
  `DashboardV2NavigationBar.kt:89` — same concept, same icon, consistent across both dashboard skins).

2 remaining, with no equally obvious existing match found:
- **Profiles** — no shared "Profile" nav icon found in this search; plan-writing should do one more
  targeted look (e.g. `Icons.Default.Assignment` or similar) and pick one Material icon, a cosmetic,
  reversible choice.
- **Pump** — same situation; the Pump *pill* on the main dashboard uses a custom Glyco drawable
  (`R.drawable.ic_glyco_insulin`) styled specifically for the pill grid, not a generic Material icon
  suited to a nav bar — plan-writing should pick a plain Material icon (e.g. `Icons.Default.Medication`
  is already taken by Bolus; something distinct like a battery/device-style icon) rather than reuse the
  Glyco drawable.

Both Profiles and Pump need one new string resource each for their nav label (Treatments/Scenarios/Management
reuse existing shared strings; Bolus can reuse `CoreUiR.string.treatments`'s sibling bolus string if one
exists, otherwise also gets a new one) — exact resource names to be picked at plan-writing time following
the project's plain-English string convention.
