# Dashboard V2 + Glass skins — Port commonMain (8 Oct 2026)

## Ported (5 files, `:core:ui` commonMain, zero `android.*`)

| File | Source | Notes |
|------|--------|-------|
| `compose/glass/GlassCard.kt` | `core/ui/.../glass/` | Pure Compose, direct port |
| `compose/glass/GlassColors.kt` | `core/ui/.../glass/` | Pure Compose, direct port |
| `compose/glass/GlassScreenKit.kt` | `core/ui/.../glass/` | Uses `StringKey`, `UiMode`, `LocalPreferences` — all in commonMain |
| `compose/glass/GlassSectionCard.kt` | `core/ui/.../glass/` | Pure Compose, direct port |
| `compose/dashboard/GlucoseHeroRing.kt` | `core/ui/.../dashboard/` | Pure Compose (Canvas), direct port |

## Blocked — Dashboard V2 screens (19 files, `plugins/main/.../dashboard/compose/`)

These depend on Android-only interfaces not yet in commonMain. Porting them requires
porting the data layer first (out of scope for this lot).

### Missing interfaces in commonMain
- `app.aaps.core.interfaces.notifications.Notification`
- `app.aaps.core.interfaces.overview.graph.BgDataPoint`, `BgType`
- `app.aaps.core.interfaces.source.CgmSensorLifecycle`, `CgmStagingEvidence`, `CgmWarmupStatus`, `StagingState`
- `app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState`
- `app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState`
- `app.aaps.plugins.main.general.dashboard.views.CircleTopActionListener`
- `app.aaps.plugins.main.general.overview.notifications.NotificationStore`

### Files by blocking reason

**Tier 1 — Need interfaces ported** (0 android, 0 VM, but use missing interfaces + `R.string`):
- `DashboardAdjustmentComposeCard.kt` — uses `R.string`, `Notification`
- `DashboardGraphComposeControls.kt` — uses `R.string`
- `DashboardGraphComposeRenderer.kt` — uses `BgDataPoint`
- `DashboardHeroCommands.kt` — clean, but depends on Tier 3 types
- `DashboardHeroLayoutProfile.kt` — pure data, portable IF needed standalone
- `DashboardMetricIcon.kt` — clean
- `DashboardNotificationsComposeList.kt` — uses `Notification`, `R.string`
- `DashboardScenarioProjectionLegend.kt` — clean
- `DashboardStagingCard.kt` — uses `Cgm*` interfaces, `R.string`
- `GraphRefreshPolicy.kt` — uses `R.string`
- `GraphStatusPresenter.kt` — uses `R.string`
- `ScenarioNearBgHint.kt` — clean

**Tier 2 — Need Android seams** (android.* imports):
- `DashboardComposeHeroUiMapper.kt` — `Context`, `DateFormat`, `TypedValue` → use `AimiDateFormatter`, pass dimensions as params
- `DashboardQuickActionsBar.kt` — `Context`, `HapticFeedbackConstants`, `AccessibilityManager` → haptics seam, accessibility optional

**Tier 3 — Need ViewModel → state hoisting**:
- `AimiAdaptationStatusScreen.kt` — 3 VM refs
- `DashboardBgGraphVico.kt` — 6 VM refs (`GraphViewModel`)
- `DashboardGraphComposeCard.kt` — 20 VM refs (`GraphViewModel`, heavy)
- `DashboardVicoSharedViewportEffects.kt` — 5 VM refs
- `DashboardCircleTopCompose.kt` — `AndroidView(FrameLayout)` for auditor host → replace with `@Composable` slot

**Blocked — App host**:
- `app/src/main/kotlin/app/aaps/compose/dashboard/DashboardOverviewHost.kt` — app module, navigation + DI, too coupled

## Recommended next steps
1. Port the missing `core/interfaces` to commonMain (Notification, BgDataPoint, Cgm*).
2. Port Tier 1 files with string params replacing `R.string`.
3. Port Tier 2 with seams.
4. Port Tier 3 with hoisted state (biggest work: `DashboardGraphComposeCard`).
