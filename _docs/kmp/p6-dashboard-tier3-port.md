# Dashboard Tier 3 port — 9 Oct 2026

## Ported (4 files, `:core:ui` commonMain)

All with zero `android.*` / `java.*` imports (verified by grep).

1. **`AimiAdaptationStatusScreen.kt`**
   - `AimiAdaptationStatusViewModel.UiState` → `AimiAdaptationStatusUiState` (same shape, pure Kotlin)
   - All `R.string` lookups → `AimiAdaptationStatusStrings` (22 fields, lambdas for formatted strings)
   - `java.text.DateFormat` → `formatTimestamp: (Long) -> String` lambda in strings

2. **`DashboardBgGraph.kt`** (was `DashboardBgGraphVico.kt`)
   - Vico stack (`BgGraphCompose` + `GraphViewModel` + Vico scroll/zoom) replaced by
     the commonMain Canvas `DashboardGraphComposeRenderer` + hoisted `DashboardGraphRenderInput`
   - SMB tap toast → dropped (platform handles via card's `onSmbMarkerTap`)

3. **`DashboardGraphComposeCard.kt`**
   - 707-line card ported with Canvas path only (Vico path dropped — Android-only)
   - `GraphViewModel` (20 refs) → `DashboardGraphCardUiState` (pre-computed)
   - `DashboardEmbeddedComposeState` → `DashboardGraphRenderInput` + callbacks
   - `R.string`/`dimensionResource` → `DashboardGraphCardStrings` + dp constants
   - `findNearestSmbByX` ported as pure function (was private, now public for reuse)

4. **`DashboardCircleTopCompose.kt`**
   - 935-line hero ported
   - `OverviewViewModel.statusCardState` → `DashboardHeroUiState` (pre-formatted strings,
     ARGB Int colors, `TrendArrow`/`UnicornMood` enums replacing drawable res IDs)
   - `AndroidView(FrameLayout)` auditor host → `auditorContent: @Composable () -> Unit` slot
   - `Preferences` → `extendedMetrics`/`showAimiPulse` params + `onExtendedMetricsChanged`
   - `ToastUtils`/`OKDialog` → `onToast`/`onShowDialog` callbacks
   - `DashboardComposeHeroUiMapper.buildHeroState` → `heroRingState: GlucoseHeroUiState?` param
   - `colorResource` → `DashboardHeroColors`

## Blocked (1 file)

**`DashboardVicoSharedViewportEffects.kt`** — NOT PORTABLE.
Pure Vico viewport effects (scroll/zoom state, `Scroll.Absolute`, `Zoom.x`,
`timestampToX`, live-edge follow, prediction viewport bias). The commonMain
Canvas renderer (`DashboardGraphComposeRenderer`) has no scroll/zoom viewport
model — it renders a fixed time window from `DashboardGraphRenderInput`.
Porting this file would require inventing a viewport model, which violates
the "do not invent APIs" rule. The fixed-window rendering supersedes it.
