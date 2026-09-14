# Glass Bottom Navigation Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Glass dashboard skin its own 6-item bottom navigation bar (Treatments, Profiles, Bolus,
Pump, Scenarios, Management), without changing the bottom nav used by `OVERVIEW`/`DASHBOARD_V1`.

**Architecture:** Add a `NavigationRequest.Route(route: String)` variant so the existing `onNavigate`
callback can reach a raw compose-nav route (needed for the already-wired `"glass_pump_detail"` route). Add
a new `GlassNavigationBar.kt` (in `ui/.../compose/main/`, alongside `DashboardV2NavigationBar.kt`, whose
enum+`fixedOrder`+loop pattern it copies) with a 6-entry `GlassNavigationTab` enum and internal per-tab
dispatch. Wire it into `MainScreen.kt` behind a new `isGlassSkin: Boolean` parameter, computed once in
`ComposeMainActivity.kt` from the already-resolved `dashboardHomeVariant`.

**Tech Stack:** Kotlin, Jetpack Compose, existing `NavigationRequest`/`onNavigate` dispatch mechanism.

**Spec:** `docs/superpowers/specs/2026-09-11-glass-bottom-nav-design.md`

## Global Constraints

- `OVERVIEW` and `DASHBOARD_V1` must keep using `MainNavigationBar` exactly as today — do not change
  `MainNavigationBar.kt` or `DashboardV2NavigationBar.kt`.
- `ui` module cannot depend on the `app` module (wrong dependency direction) — the `"glass_pump_detail"`
  route must stay a raw string literal in `GlassNavigationBar.kt`, matching the existing literal already
  used by `DashboardShellController.kt`'s `GlassHeroCommands.openPump()` (in `plugins:main`, same
  constraint). Do not try to import `AppRoute.GlassPumpDetail` from `ui`.
- Reuse existing strings/icons where they already exist (`CoreUiR.string.treatments`,
  `CoreUiR.string.scenes`, `CoreUiR.string.manage`, `app.aaps.ui.R.string.dashboard_v2_nav_bolus`) — do not
  create near-duplicate strings for the same words.
- English-only new string resources — do not touch `values-fr/` or `values-fr-rFR/`.
- Run `./gradlew :ui:compileFullDebugKotlin --no-daemon` and `./gradlew :app:compileFullDebugKotlin
  --no-daemon` after each task (both modules are touched across the 2 tasks). Run
  `./gradlew :ui:compileFullDebugUnitTestKotlin --no-daemon` too if a task touches a test file.
- Do not run `git commit` — leave all work in the working tree for the user to review.

---

### Task 1: `NavigationRequest.Route` + new `GlassNavigationBar.kt`

**Files:**
- Modify: `core/ui/src/main/kotlin/app/aaps/core/ui/compose/navigation/NavigationRequest.kt`
- Modify: `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt` (the `handleNavigationRequest` function only)
- Create: `ui/src/main/kotlin/app/aaps/ui/compose/main/GlassNavigationBar.kt`
- Modify: `ui/src/main/res/values/strings.xml` (2 new strings: Profiles, Pump)

**Interfaces:**
- Produces: `GlassNavigationBar(masterOrPairedClient: Boolean, onTreatmentClick: () -> Unit,
  onScenariosClick: () -> Unit, onManagementClick: () -> Unit, onNavigate: (NavigationRequest) -> Unit,
  modifier: Modifier = Modifier)` — consumed by Task 2 in `MainScreen.kt`. Also produces
  `NavigationRequest.Route(val route: String)`, a new sealed subclass Task 2 does not need directly (it's
  used internally by `GlassNavigationBar`'s own Pump-tab dispatch) but must not break `MainScreen.kt`'s
  existing `onNavigate` callers, which stay untouched.

- [ ] **Step 1: Add the `Route` variant to `NavigationRequest`**

In `core/ui/src/main/kotlin/app/aaps/core/ui/compose/navigation/NavigationRequest.kt`, change:

```kotlin
sealed class NavigationRequest {
    data class Element(val type: ElementType) : NavigationRequest()
    data class QuickWizard(val guid: String) : NavigationRequest()
    data class Plugin(val className: String) : NavigationRequest()
    data class PluginPreferences(val pluginKey: String) : NavigationRequest()
}
```

to:

```kotlin
sealed class NavigationRequest {
    data class Element(val type: ElementType) : NavigationRequest()
    data class QuickWizard(val guid: String) : NavigationRequest()
    data class Plugin(val className: String) : NavigationRequest()
    data class PluginPreferences(val pluginKey: String) : NavigationRequest()

    /** Navigates to a raw compose-nav route string, unprotected. Used by routes that have no
     *  [ElementType] of their own — e.g. Glass's `"glass_pump_detail"`. */
    data class Route(val route: String) : NavigationRequest()
}
```

- [ ] **Step 2: Handle `Route` in `ComposeMainActivity.handleNavigationRequest`**

In `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt`, find `handleNavigationRequest` (the `when
(request) { ... }` block that currently has 4 branches — `Element`, `QuickWizard`, `Plugin`,
`PluginPreferences`). Add a 5th branch:

```kotlin
            is NavigationRequest.Route              -> navController.navigate(request.route)
```

Place it anywhere among the existing branches (order does not matter for a `when` over a sealed class).

- [ ] **Step 3: Add the 2 new string resources**

In `ui/src/main/res/values/strings.xml`, add these 2 lines near the existing `dashboard_v2_nav_*` strings
(around where `dashboard_v2_nav_bolus` is defined):

```xml
    <string name="dashboard_glass_nav_profiles">Profiles</string>
    <string name="dashboard_glass_nav_pump">Pump</string>
```

Do not touch `values-fr/strings.xml` or `values-fr-rFR/strings.xml`.

- [ ] **Step 4: Create `GlassNavigationBar.kt`**

Create `ui/src/main/kotlin/app/aaps/ui/compose/main/GlassNavigationBar.kt` with this exact content:

```kotlin
@file:Suppress("ktlint:standard:function-naming")

package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.R
import app.aaps.core.ui.R as CoreUiR

/** Stable six-item navigation used only by the Glass home shell. */
enum class GlassNavigationTab {
    TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT;

    companion object {
        val fixedOrder: List<GlassNavigationTab> = listOf(TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT)
    }
}

private const val GLASS_PUMP_DETAIL_ROUTE = "glass_pump_detail"

@Composable
fun GlassNavigationBar(
    masterOrPairedClient: Boolean,
    onTreatmentClick: () -> Unit,
    onScenariosClick: () -> Unit,
    onManagementClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(0),
        modifier = modifier,
    ) {
        GlassNavigationTab.fixedOrder.forEach { tab ->
            // Treatments/Scenarios are mutating actions that need a paired client on a non-master install —
            // same rule MainNavigationBar already applies to its own Treatments/Scenes buttons.
            val enabled = masterOrPairedClient || tab !in setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS)
            NavigationBarItem(
                selected = false,
                enabled = enabled,
                onClick = {
                    when (tab) {
                        GlassNavigationTab.TREATMENTS -> onTreatmentClick()
                        GlassNavigationTab.SCENARIOS   -> onScenariosClick()
                        GlassNavigationTab.MANAGEMENT  -> onManagementClick()
                        GlassNavigationTab.PROFILES    -> onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT))
                        GlassNavigationTab.BOLUS        -> onNavigate(NavigationRequest.Element(ElementType.BOLUS_WIZARD))
                        GlassNavigationTab.PUMP         -> onNavigate(NavigationRequest.Route(GLASS_PUMP_DETAIL_ROUTE))
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = stringResource(tab.labelRes()),
                    )
                },
                label = { Text(text = stringResource(tab.labelRes())) },
                colors = colors,
            )
        }
    }
}

private fun GlassNavigationTab.icon(): ImageVector =
    when (this) {
        GlassNavigationTab.TREATMENTS -> Icons.Default.Fastfood
        GlassNavigationTab.PROFILES   -> Icons.AutoMirrored.Filled.Assignment
        GlassNavigationTab.BOLUS      -> Icons.Default.Medication
        GlassNavigationTab.PUMP       -> Icons.Default.Vaccines
        GlassNavigationTab.SCENARIOS  -> IcAutomation
        GlassNavigationTab.MANAGEMENT -> Icons.Default.ManageAccounts
    }

private fun GlassNavigationTab.labelRes(): Int =
    when (this) {
        GlassNavigationTab.TREATMENTS -> CoreUiR.string.treatments
        GlassNavigationTab.PROFILES   -> R.string.dashboard_glass_nav_profiles
        GlassNavigationTab.BOLUS      -> R.string.dashboard_v2_nav_bolus
        GlassNavigationTab.PUMP       -> R.string.dashboard_glass_nav_pump
        GlassNavigationTab.SCENARIOS  -> CoreUiR.string.scenes
        GlassNavigationTab.MANAGEMENT -> CoreUiR.string.manage
    }
```

(`R.string.dashboard_v2_nav_bolus` resolves to `app.aaps.ui.R` here — same module as
`DashboardV2NavigationBar.kt`, which already uses that exact string. `IcAutomation` is the same icon
`MainNavigationBar.kt` already uses for its own Scenes button.)

- [ ] **Step 5: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`, then `./gradlew :app:compileFullDebugKotlin
--no-daemon`. Both must succeed. (`:app` is included because Step 2 touches `ComposeMainActivity.kt`.)

- [ ] **Step 6: Commit is NOT performed by the implementer**

Do not run `git commit`. Leave the changes in the working tree.

---

### Task 2: Wire `GlassNavigationBar` into `MainScreen.kt` for the Glass skin only

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/main/MainScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt` (the `MainScreen(...)` call site only)

**Interfaces:**
- Consumes: `GlassNavigationBar(...)` from Task 1 (exact parameter names above), and the existing
  `dashboardHomeVariant`/`DashboardHomeVariant.GLASS` already resolved in `ComposeMainActivity.kt`.

- [ ] **Step 1: Add `isGlassSkin` parameter to `MainScreen`**

In `ui/src/main/kotlin/app/aaps/ui/compose/main/MainScreen.kt`, find the `fun MainScreen(` parameter list.
It already has (among others):

```kotlin
    dashboardOverview: (@Composable (PaddingValues, Dp) -> Unit)? = null,
    ...
    dashboardTools: (@Composable (PaddingValues, Dp) -> Unit)? = null,
```

Add one more parameter, right after `dashboardTools`:

```kotlin
    /** True only for the GLASS dashboard skin — swaps in [GlassNavigationBar] instead of the default
     *  [MainNavigationBar]. Does not affect OVERVIEW/DASHBOARD_V1, which keep using MainNavigationBar. */
    isGlassSkin: Boolean = false,
```

- [ ] **Step 2: Branch on `isGlassSkin` in the bottom-bar block**

Find the bottom-bar `AnimatedVisibility` block (`if (dashboardTools != null) { DashboardV2NavigationBar(...)
} else MainNavigationBar(...)`). Change the `else` to check `isGlassSkin` first:

```kotlin
                            if (dashboardTools != null) {
                                DashboardV2NavigationBar(
                                    selectedTab = dashboardV2SelectedTab,
                                    masterOrPairedClient = masterOrPairedClient,
                                    onTabClick = { tab ->
                                        when (tab) {
                                            DashboardV2NavigationTab.MAIN,
                                            DashboardV2NavigationTab.TOOLS -> dashboardV2SelectedTab = tab

                                            DashboardV2NavigationTab.BOLUS,
                                            DashboardV2NavigationTab.CONFIGURATION,
                                            DashboardV2NavigationTab.PREFERENCES -> {
                                                tab.elementType?.let { type ->
                                                    onNavigate(NavigationRequest.Element(type))
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.onSizeChanged {
                                        if (it.height > 0 && it.height != bottomBarHeightPx) bottomBarHeightPx = it.height
                                    },
                                )
                            } else if (isGlassSkin) {
                                GlassNavigationBar(
                                    masterOrPairedClient = masterOrPairedClient,
                                    onTreatmentClick = {
                                        treatmentViewModel.refreshState()
                                        showTreatmentSheet = true
                                    },
                                    onScenariosClick = {
                                        scenesViewModel.refreshState()
                                        showAutomationSheet = true
                                    },
                                    onManagementClick = { manageSheetState.show() },
                                    onNavigate = onNavigate,
                                    modifier = Modifier.onSizeChanged {
                                        if (it.height > 0 && it.height != bottomBarHeightPx) bottomBarHeightPx = it.height
                                    },
                                )
                            } else MainNavigationBar(
```

(The rest of the existing `MainNavigationBar(...)` call — all its other parameters — stays exactly as it is
today; only the `else` became `else if (isGlassSkin) { ... } else`.)

- [ ] **Step 3: Pass `isGlassSkin` from `ComposeMainActivity.kt`**

In `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt`, find the `MainScreen(` call (around line 765,
inside `key(dashboardHomeVariant, generalSkin) { ... }`). It already passes `dashboardTools = if
(dashboardHomeVariant == DashboardHomeVariant.DASHBOARD_V2) { ... } else null` somewhere in its argument
list. Add, near that same argument:

```kotlin
                        isGlassSkin = dashboardHomeVariant == DashboardHomeVariant.GLASS,
```

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`, then `./gradlew :app:compileFullDebugKotlin
--no-daemon`. Both must succeed.

- [ ] **Step 5: Commit is NOT performed by the implementer**

Do not run `git commit`. Leave the changes in the working tree.
