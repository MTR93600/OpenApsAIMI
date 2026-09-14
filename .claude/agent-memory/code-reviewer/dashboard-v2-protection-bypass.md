---
name: dashboard-v2-protection-bypass
description: DASHBOARD_V2 quick-action chips (carbs/wizard/quick-wizard-mgmt/temp-target-mgmt) bypass the app's ElementType.protection PIN/biometric gate; how to check for this class of bug
metadata:
  type: project
---

`uiInteraction.openComposeMainAtRoute(context, "route_string")` (declared in
`core/interfaces/.../ui/UiInteraction.kt`, impl in `app/.../implementations/UiInteractionImpl.kt`)
is a **raw, unprotected** navigation escape hatch. It just fires an Intent with
`ComposeMainActivity.EXTRA_NAVIGATE_ROUTE`; `ComposeMainActivity.onNewIntent` /
the `MainContent()` `LaunchedEffect` reads that extra and calls
`navController.navigate(route)` directly — no protection check anywhere in that path.

Every other entry point to a sensitive screen goes through
`ElementType.protection` (`core/interfaces/.../navigation/ElementType.kt`) via
`ComposeMainActivity.navigateProtected(elementType, navController)` (checks
`elementType.protection`, calls `protectionCheck.requestAuthorization(minLevel)` before
navigating) or `withProtection(protection) { ... }`. This applies to `QuickLaunchToolbar`
(`QuickLaunchAction.StaticAction` → `navigateProtected`), search results
(`SearchableItem.Dialog` → `navigateProtected`), `ManageBottomSheet`,
`OverviewChipsColumn`, etc.

`ElementType.CARBS`, `INSULIN`, `BOLUS_WIZARD`, `QUICK_WIZARD_MANAGEMENT`,
`TEMP_TARGET_MANAGEMENT`, `TREATMENT`, `AFREZZA`, `TEMP_BASAL`, `EXTENDED_BOLUS`,
`CANNULA_CHANGE`, `FILL`, `RUNNING_MODE`, `SCENE` all default to
`protection = ProtectionCheck.Protection.BOLUS` (i.e. PIN/biometric gate by default, not opt-in).

**Recurring review checklist**: whenever a diff adds a new one-tap dashboard/toolbar shortcut
that opens one of these routes, verify it goes through `navigateProtected`/`withProtection`
(or the typed `UiInteraction.openXScreen(activity)` helpers that wrap it), NOT a bare
`openComposeMainAtRoute` call. `openComposeMainAtRoute` is legitimate ONLY for
non-sensitive, read-only destinations (e.g. `stats`, `treatments` history browser —
`AppRoute.Treatments`, distinct from the single-entry `ElementType.TREATMENT` dialog).

Found 2026-09-10 in `dev_OAPSAIMI_dashboard2`: `DashboardShellController`'s
`openCarbsEntry`/`openBolusWizard`/`openQuickWizardManagement`/`openTempTargetManagement`
all called raw `openComposeMainAtRoute`, skipping the BOLUS-level protection every other
path in the app enforces for those exact 4 screens. Reported as Critical (safety-relevant:
bypasses the user's configured PIN/biometric lock for carb entry, bolus wizard, and dosing
management screens).

Separately: `UiInteraction` already has typed, drift-proof helpers for some of these
(`openTempTargetManagementScreen(activity)` — internally uses the real `AppRoute` with
no string duplication) that plugins:main can call without depending on `:app`. When adding a
new inlined route-string literal, always grep `UiInteraction.kt` first for an existing typed
method before duplicating the route string by hand — cheaper to keep in sync and avoids the
protection-bypass trap since typed helpers tend to route through the activity/protected path.

See also [[dashboard-v2-migration]] if that file exists for other DASHBOARD_V2 findings.
