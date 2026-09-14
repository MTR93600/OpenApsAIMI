# Glass Dashboard Personalization — Design Spec

**Status:** Approved by user, ready for implementation planning.

## Goal

Let the user choose which compact metric pills appear on the Glass main dashboard screen, instead of a
hardcoded, always-visible bottom row. Replace the current hardcoded IOB/Target/Basal T row (which has a
display bug — see below — and makes the screen feel too tall) with a user-curated selection, picked from a
new "Personalize" screen reachable from the existing Tools overlay.

## Background

On-device feedback (screenshot, 2026-09-11) flagged two things on the Glass main screen
(`StatusAgoraCard.kt`):

1. **Bug:** the bottom-row IOB pill renders as "IOB IOB: 0,82 U" — the word "IOB" appears twice. Root cause
   (confirmed by code investigation): `StatusCardState.iobText` (`OverviewViewModel.kt`) is built as a
   literal, prefixed string `"IOB: $formattedTotal"` (in `formatDashboardIobHeader()`, `OverviewViewModel.kt:
   966-975`), shared with DASHBOARD_V2 and legacy views, which display it standalone (no separate "IOB"
   title). Glass's `BottomMetricPill` (`StatusAgoraCard.kt:286-292`) adds its own separate "IOB" title on top
   of this already-prefixed value, causing the duplicate.
2. **Feedback:** the user likes the design overall but finds the screen too tall/scrolly, and wants the bottom
   row hidden by default, with the ability to personalize what appears there — pulling from data the app
   already computes elsewhere (Treatments, Gestion/Management screens) — via the Tools screen.

## Decisions already made with the user

- **Mechanism:** a curated, code-defined catalog of "pills" (not free-form embedding of arbitrary screen
  content) — the user explicitly chose "compact pills/shortcuts now, richer cards possibly later" over
  building a heavier generic widget system up front.
- **Current row's fate:** IOB, Target, and Basal T (with the IOB bug fixed) become 3 entries in the new
  catalog, not a hardcoded permanent row. Default selection: **none** — the bottom row is hidden until the
  user opts in, matching the "screen feels too tall" complaint.
- **Entry point:** a new Glass-only "Personalize" screen, reached from within the existing Tools overlay (NOT
  inside `DashboardV2ToolsScreen` itself, which is a shared component also used by DASHBOARD_V2 and must not
  gain Glass-specific UI, per an earlier plan's explicit constraint).
- **Visual style:** the new Personalize screen adopts the "GlycoCalm" visual language (see
  `docs/superpowers/specs/2026-09-11-glycocalm-reference/` — `DESIGN.md` + `code.html`, provided by the user
  as reference material from a collaborator using Google's Stitch tool). This does NOT restyle any existing
  Glass screen — only new screens/content created by this plan and the companion
  `2026-09-11-glass-loop-dashboard-expansion-design.md` plan adopt it.

## Architecture

### Data model (new file: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalization.kt`)

```kotlin
enum class GlassPillId { IOB, TARGET, BASAL_RATE, LAST_BOLUS, LAST_CARBS }

data class GlassPillCatalogEntry(
    val id: GlassPillId,
    val labelRes: Int,
    // value text is resolved from GlassUiState/GlassChartState at render time, not stored here
)

val GLASS_PILL_CATALOG: List<GlassPillCatalogEntry> = listOf(
    GlassPillCatalogEntry(GlassPillId.IOB, R.string.dashboard_glass_iob_label),
    GlassPillCatalogEntry(GlassPillId.TARGET, R.string.dashboard_glass_target_label),
    GlassPillCatalogEntry(GlassPillId.BASAL_RATE, R.string.dashboard_glass_basal_label),
    GlassPillCatalogEntry(GlassPillId.LAST_BOLUS, R.string.dashboard_glass_last_bolus_label),
    GlassPillCatalogEntry(GlassPillId.LAST_CARBS, R.string.dashboard_glass_last_carbs_label),
)
```

**v1 catalog scope (5 entries):** IOB, Target, and Basal T are the existing 3 (data already available via
`GlassUiState`/`StatusCardState` — already wired). Last Bolus and Last Carbs are new but cheap: the amount +
relative time of the most recent entry in `treatmentData.boluses`/`treatmentData.carbs`
(`GraphViewModel.treatmentGraphFlow`, already collected in `GlassOverviewComposeEmbedded.kt` for the BG
chart's treatment markers — this plan only needs to also read the *latest* entry for pill text, not add a new
data source). Exact field names/computation for Last Bolus/Last Carbs to be confirmed during plan-writing (the
data is already in scope, just needs a "most recent" reduction).

**IOB bug fix (folds into this work, not a separate task):** add a new field to `StatusCardState`
(`OverviewViewModel.kt`) carrying the IOB total WITHOUT the `"IOB: "` prefix — e.g. `iobValueText`, computed
inline where `iobText` already is (same `resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units,
total)` call, just not concatenated with a literal prefix). `iobText` itself stays untouched (DASHBOARD_V2 and
legacy views keep using it as-is). Glass's IOB catalog pill uses the new `iobValueText` field, so its own
title (from `GLASS_PILL_CATALOG`) is the only place "IOB" appears. Target and Basal T do NOT have this bug —
their existing value text doesn't repeat the pill's title — so they migrate into the catalog unchanged.

### Persistence

A new preference, e.g. `StringKey.GlassSelectedPills`, storing a comma-separated list of `GlassPillId.name`
values. Read via the same `LocalPreferences.current` / `preferences.observe(StringKey.X).collectAsState()`
pattern already used in `GlassOverviewComposeEmbedded.kt` for dark-mode sourcing. Default: empty string (no
pills shown). The exact write API (which existing preferences-mutation call this codebase already uses
elsewhere for a similar list-type setting) is to be confirmed during plan-writing — do not invent a new
preferences mechanism if an existing multi-value preference pattern already exists in this codebase.

### UI — Personalize screen

New file, e.g. `GlassPersonalizeScreen.kt`, Glass-only, GlycoCalm-styled (see Visual style below): a
scrollable checklist, one row per `GLASS_PILL_CATALOG` entry (icon + label + checkbox/toggle), auto-saving to
the preference on each toggle (no separate "Save" button, matching the live-apply pattern already used for
other AAPS preferences).

**Entry point:** inside `GlassOverviewComposeEmbedded.kt`'s existing Tools overlay (`if (showTools) { ... }`
block, added by an earlier plan), add a small header row ABOVE the existing `DashboardV2ToolsScreen(...)` call
— NOT inside it — with a "Personnaliser" button. A new local state (e.g. `toolsOverlayMode: ToolsOverlayMode`
with values `GRID` / `PERSONALIZE`) switches the overlay body between the existing Tools grid and the new
Personalize screen. The existing `BackHandler(enabled = showTools) { showTools = false }` (added in the prior
plan's final-review fix wave) should first return `PERSONALIZE → GRID` if in that mode, and only close the
whole overlay (`showTools = false`) on a second back-press from `GRID` — avoid regressing the already-fixed
"no way to back out of Tools" bug.

### Rendering on the main screen

`StatusAgoraCard.kt`'s current hardcoded bottom row (`BottomMetricPill` × 3: IOB, Target, Basal T) is removed.
`GlassOverviewComposeEmbedded.kt` reads the selected-pill preference, filters `GLASS_PILL_CATALOG` by
selection, and renders a new row of `BottomMetricPill`s built from that filtered list — the row is omitted
entirely (not shown as an empty container) when the selection is empty.

## Visual style — GlycoCalm (new screens only)

Reference: `docs/superpowers/specs/2026-09-11-glycocalm-reference/DESIGN.md` (design tokens + component
guidance) and `code.html` (concrete HTML/Tailwind example). Applies to: the new Personalize screen in this
plan, and the new Loop Dashboard sections in the companion plan (`2026-09-11-glass-loop-dashboard-expansion-
design.md`, which also restyles the whole Loop Dashboard screen for consistency). Does NOT apply to
`StatusAgoraCard.kt`'s existing pills/main screen, `BgChartCard`/`IobChartCard`, or any other already-shipped
Glass screen — those keep their current dark-gradient visual language.

Key tokens to carry into Compose (exact `Color(...)` literals to be pinned during plan-writing, following this
codebase's established convention of plain hex literals per file rather than importing a shared theme object —
see `GlassChartComponents.kt`'s own style for precedent):
- Primary: `#0D1B2A` (Deep Obsidian Slate) — primary buttons/headers.
- Secondary/positive: `#00B894` (Emerald Mint) — in-range/confirmation accents.
- Tertiary: `#0984E3` (Electric Cerulean) — secondary metrics, interactive controls.
- Neutral text: `#64748B` (Cool Slate) — labels, units, metadata.
- Canvas background: `#F1F5F9`; cards: `#FFFFFF`/`#F8FAFC`.
- Cards: `rounded-2xl`/`rounded-3xl` (20-24dp), ambient diffused shadow (no hard borders), pill-shaped
  buttons/chips.
- Typography: bold numeric values (weight 800) paired with a small muted unit label — mirrors this codebase's
  own `GlassPill`/`BottomMetricPill` convention of a bold value + separate muted label, just lighter/rounder.

**Dark mode:** every existing Glass screen sources `isDark` via the established `LocalPreferences`/
`StringKey.GeneralDarkMode`/`UiMode` chain (never `isSystemInDarkTheme()` directly) — this is a standing
Global Constraint across this whole Glass skin, and the new Personalize screen must follow it too, not be
permanently light. `DESIGN.md`'s token set already includes a set of `inverse-*`/`*-container`/`*-fixed-dim`
tokens (e.g. `inverse-surface: #213145`, `inverse-on-surface: #eaf1ff`, `primary-container: #0f1c2c`) that read
as an intended dark-mode counterpart to the light tokens described in prose above — use these as the starting
point for a GlycoCalm dark variant, refined at plan-writing time rather than inventing an unrelated dark
palette.

## Non-goals / deferred

- Richer embedded cards/widgets (full mini-screens pulled from Treatments/Gestion) — explicitly deferred by
  the user to a possible future phase, once this simpler pill-catalog mechanism is in place and proven.
  The data model above (`GlassPillCatalogEntry`) is intentionally light so it does not block a future,
  larger widget type being added to the same catalog list.
- Reordering/drag-and-drop of selected pills — not requested; selection order in v1 follows the catalog's own
  fixed order, not user-chosen order.
- Restyling any existing (already-shipped) Glass screen to GlycoCalm — explicitly out of scope for this round;
  a possible separate future project if the user likes the new screens' look.

## Open items for plan-writing

- Exact field/property names for Last Bolus / Last Carbs "most recent entry" extraction from
  `treatmentData.boluses`/`treatmentData.carbs`.
- Exact preference read/write API for a multi-value `StringKey`-style preference (confirm an existing pattern
  in this codebase rather than inventing one).
- Exact hex `Color(...)` values and Compose component structure for the GlycoCalm-styled Personalize screen,
  translated from the reference `DESIGN.md`/`code.html`.
