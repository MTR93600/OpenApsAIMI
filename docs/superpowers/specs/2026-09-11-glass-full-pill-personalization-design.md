# Glass Full Pill Personalization — Design Spec

**Status:** Approved by user, ready for implementation planning.

## Goal

Extend the existing pill-personalization system (currently 5 bottom-row entries: IOB, Target, Basal T, Last
Bolus, Last Carbs) to cover the 7 top-grid pills too (Insulin/Pump reservoir, Cannula, Battery, Tools, Sensor,
Loop, Activity), so the user can hide/show ANY pill on the Glass main screen, not just the bottom row.

## Background

On-device feedback (2026-09-11, after testing the already-shipped Personalization round): "il faudrait que
tous les items soit présent, par exemple si je n'ai pas besoin d'afficher la batterie, je peux le retirer" —
all items should be personalizable, e.g. Battery should be removable if not needed. The existing
`GlassPillId`/`GLASS_PILL_CATALOG`/`GlassPersonalizeScreen` system already does exactly this for the bottom
row; this spec extends it to the top grid.

## Current state (`StatusAgoraCard.kt`)

The top grid has 2 columns, laid out as individual hand-written `GlassPill(...)` calls, each with its own
icon/click-behavior/content — heterogeneous, not a uniform list like the bottom row's `BottomMetricPill`s:

- **LEFT column** (4 pills): Insulin/Pump reservoir (`onOpenPump`), Cannula (`onOpenCannula`), Battery
  (`onOpenBattery`), Tools (`onOpenTools`).
- **CENTER**: the glucose readout (current BG, delta, time-ago) — NOT a pill, always shown, not part of this
  personalization system.
- **RIGHT column** (3 pills): Sensor (`combinedClickable`: short→`onOpenSensorQuality`, long→
  `onOpenSensorInsert`), Loop (`combinedClickable`: short→`onOpenLoopDashboard`, long→`onOpenLoop`, plus an
  animated pulse dot), Activity (steps · heart rate, no click handler).

## Decisions already made with the user

- **All 7 top-grid pills become optional**, joining the existing 5 bottom-row entries — a unified 12-entry
  catalog, not two separate concepts from the user's point of view (even though the implementation keeps them
  architecturally distinct, see below).
- **Approach**: keep each top-grid pill's existing hand-written Composable call (icon, click behavior,
  animated content) UNCHANGED — just gate each one behind `if (id in effectiveSelectedPills)`. Do NOT
  generalize the heterogeneous top grid into a single data-driven loop like the bottom row's — the 7 pills are
  too visually/behaviorally different (custom icons, `combinedClickable` short/long press, an animated pulse
  indicator, a no-click Activity pill) for that to be a clean, low-risk refactor. When a pill is hidden,
  Compose simply doesn't emit it — the enclosing `Column`'s `spacedBy` gap collapses naturally, no extra
  layout code needed.
- **Default-visible, not default-hidden, for the 7 new entries** — unlike the bottom row (which intentionally
  defaults to hidden, opt-in), the top-grid pills are ALREADY always shown today; making them default-hidden
  would be a silent regression for anyone who's never opened Personalize. See Architecture below for exactly
  how this is achieved without breaking the existing preference format.

## Architecture

### Data model (`GlassPersonalization.kt`)

Add 7 new `GlassPillId` values and extend `GlassPillCatalogEntry` with a `defaultSelected: Boolean` flag:

```kotlin
enum class GlassPillId {
    IOB, TARGET, BASAL_RATE, LAST_BOLUS, LAST_CARBS,               // existing, bottom row, default-hidden
    PUMP_RESERVOIR, CANNULA, BATTERY, TOOLS_SHORTCUT, SENSOR, LOOP_STATUS, ACTIVITY,  // new, top grid, default-visible
}

data class GlassPillCatalogEntry(
    val id: GlassPillId,
    val labelRes: Int,
    val defaultSelected: Boolean = false,
)

val GLASS_PILL_CATALOG: List<GlassPillCatalogEntry> = listOf(
    GlassPillCatalogEntry(GlassPillId.IOB, R.string.dashboard_glass_iob_label),
    GlassPillCatalogEntry(GlassPillId.TARGET, R.string.dashboard_glass_target_label),
    GlassPillCatalogEntry(GlassPillId.BASAL_RATE, R.string.dashboard_glass_basal_t_label),
    GlassPillCatalogEntry(GlassPillId.LAST_BOLUS, R.string.dashboard_glass_last_bolus_label),
    GlassPillCatalogEntry(GlassPillId.LAST_CARBS, R.string.dashboard_glass_last_carbs_label),
    GlassPillCatalogEntry(GlassPillId.PUMP_RESERVOIR, R.string.dashboard_glass_personalize_pump_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.CANNULA, R.string.dashboard_glass_personalize_cannula_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.BATTERY, R.string.dashboard_glass_personalize_battery_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.TOOLS_SHORTCUT, R.string.dashboard_glass_personalize_tools_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.SENSOR, R.string.dashboard_glass_personalize_sensor_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.LOOP_STATUS, R.string.dashboard_glass_personalize_loop_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.ACTIVITY, R.string.dashboard_glass_personalize_activity_label, defaultSelected = true),
)
```

**Migration/default-application rule (revised during plan self-review — see below):** the original draft of
this rule said "if the raw stored string is exactly empty, apply catalog defaults; otherwise use it as-is."
That is ambiguous: a fresh install (never touched Personalize) and a user who explicitly unchecks every pill
both serialize to the same empty string `""` — the fresh-install case should show the 7 default-visible pills,
but the explicit-clear-everything case should show none, and the two cases are indistinguishable at read time
under that rule.

The fix: change `GlassSelectedPills`'s **compiled-in class default** (in `core/keys/.../StringNonKey.kt`) from
`""` to a literal, comma-joined string of the 7 new default-selected `GlassPillId` names:
`"PUMP_RESERVOIR,CANNULA,BATTERY,TOOLS_SHORTCUT,SENSOR,LOOP_STATUS,ACTIVITY"`. `core:keys` is a lower module
that `plugins:main` depends on (not the reverse), so this constant cannot reference `GLASS_PILL_CATALOG`
directly — it is a plain string literal that must be kept in sync with the catalog's `defaultSelected` flags;
add a one-line comment at the declaration cross-referencing `GLASS_PILL_CATALOG` in `GlassPersonalization.kt`
so a future change to one prompts a check of the other.

With this fix, `preferences.observe(StringNonKey.GlassSelectedPills)` / `.get(...)` already returns the
correct default string for any install that has never written a value — no special-casing needed at the read
site. `GlassOverviewComposeEmbedded.kt` goes back to a plain `parseSelectedGlassPills(selectedPillsRaw)` call,
exactly like the existing bottom-row code today. An install that already has a non-empty PERSISTED value from
testing an earlier pre-release build (true for the user's own current test device — it already saved a value
under the OLD 5-entry-only catalog) keeps that literal stored value after upgrade, so the 7 new pills will
show as unchecked there until manually re-checked once in Personalize — a one-time, low-stakes step for a
pre-release build, not a production migration concern (unchanged conclusion from the original draft, now
reached through a correct mechanism instead of an ambiguous one).

### `GlassPersonalizeScreen.kt`

No structural change — it already renders `GLASS_PILL_CATALOG.forEach { ... }` as checkbox rows; the 7 new
entries just appear automatically once added to the catalog list. Consider a light visual grouping (a small
section header "Main screen" vs "Bottom row", or just accept a flat 12-row list) — a cosmetic detail to decide
at plan-writing time, not a functional requirement.

### `StatusAgoraCard.kt`

`selectedPills: List<GlassPillId>` is ALREADY a parameter (currently used only to filter the bottom row via
`GLASS_PILL_CATALOG.filter { it.id in selectedPills }`, lines ~289-314). Wrap each of the 7 top-grid pill
calls in `if (GlassPillId.X in selectedPills) { GlassPill(...) }` — no other change to each pill's own body.

### `GlassOverviewComposeEmbedded.kt`

No change needed beyond adding the 7 new `if` guards described above — `val selectedPills =
remember(selectedPillsRaw) { parseSelectedGlassPills(selectedPillsRaw) }` (line 88) already resolves to the
correct default set once the `StringNonKey.GlassSelectedPills` class default is fixed (see Migration rule
above), so this call site needs no special-casing.

### `core/keys/src/main/kotlin/app/aaps/core/keys/StringNonKey.kt`

Change line 43 from `GlassSelectedPills(key = "glass_selected_pills", defaultValue = "", exportable = false)`
to the new literal default string described in the Migration rule above.

## Non-goals / deferred

- No change to the CENTER glucose readout — never optional.
- No visual redesign of any individual pill — same icons, same click behavior, same styling, just
  conditionally rendered.

## Resolved during self-review (were open items, now settled)

- `StatusAgoraCard`'s `selectedPills` param is confirmed `List<GlassPillId>` (`StatusAgoraCard.kt:65`) — the
  `if (id in selectedPills)` guards work directly against it, no type change needed.
- The Personalize checklist needs its own 7 new static string resources (the existing top-grid pills' own
  on-screen labels — `state.insulinLabel`, `cannulaLabel`, etc. — are DYNAMIC/data-driven, not fixed string
  resources): plain, simple English names — "Pump", "Cannula", "Battery", "Tools", "Sensor", "Loop",
  "Activity" — not the dynamic on-pill text. Exact resource names to be picked at plan-writing time following
  the existing `dashboard_glass_personalize_*` naming convention (matches the `labelRes` names already used in
  the Architecture section above).
- A correctness bug was found and fixed in this self-review pass: the original default-application rule could
  not distinguish "fresh install, never touched Personalize" from "user explicitly unchecked every pill" (both
  serialize to `""`). Fixed by moving the default from a read-time special case to the preference key's own
  compiled-in class default (see Migration rule above) — this removes the ambiguity entirely instead of
  working around it.
