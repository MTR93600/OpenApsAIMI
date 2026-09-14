---
name: vico-chart-markers
description: Vico 3.3.0 CartesianMarkerController contract (verified against sources jar) and a recurring stale-tooltip bug pattern in ui/compose/overview/graphs
metadata:
  type: project
---

## Where to look
- `ui/src/main/kotlin/app/aaps/ui/compose/overview/graphs/BgGraphCompose.kt` and
  `SecondaryGraphCompose.kt` hold the Vico chart code (BG graph, IOB/basal secondary graph).
  Both use `com.patrykandpatrick.vico:compose` 3.3.0.
- Vico ships real sources jars (not just binaries) in the Gradle cache, e.g.
  `~/.gradle/caches/modules-2/files-2.1/com.patrykandpatrick.vico/compose/3.3.0/*/compose-3.3.0-sources.jar`.
  Unzip to a scratch dir and read `commonMain/.../cartesian/marker/*.kt` directly instead of
  guessing at the API or decompiling (project rule: don't decompile Vico, look for sources first).

## Confirmed CartesianMarkerController contract (from source, `CartesianMarkerController.kt`)
- `shouldAcceptInteraction(interaction, targets)`: if it returns `true`, Vico calls
  `shouldShowMarker` next. **If it returns `false`, `shouldShowMarker` is skipped entirely and
  the marker's visibility/position from the previous interaction is left exactly as it was** —
  this is a real "leave stale state on screen" behavior, not a hide.
- `shouldShowMarker(interaction, targets)`: return value directly controls whether the marker
  renders for that interaction.
- Built-in controllers for reference: `ShowOnPressMarkerController` (show while pressed, hide on
  release), `ShowOnHoverMarkerController`, `ToggleOnTapMarkerController`.

## Recurring bug pattern: stale tooltip after a "swallowed" interaction
When a custom `CartesianMarkerController` returns `false` from `shouldAcceptInteraction` to
special-case one kind of tap (e.g. AndroidAPS's `DashboardSmbTapMarkerController`, which fires a
toast on an SMB-dose tap instead of showing the value tooltip), the *previous* tooltip (from
whatever the last accepted interaction was) stays on screen, now showing a stale value/time at
the wrong position — because `shouldShowMarker` is never re-invoked for the swallowed tap. This
is only latent/harmless if the marker itself is unconditionally invisible (old code had
`shouldShowMarker` hardcoded `false`), but becomes a visible regression the moment the same
controller also drives a real value/time tooltip (seen 2026-09 when `BgGraphCompose`'s dashboard
SMB-tap controller was reused to also show a tap-to-see-BG-value/time marker: tapping a regular
point then tapping an SMB point leaves the earlier tooltip lingering, styled/positioned as if
still valid, while the SMB toast fires on top of it).
- Fix options: track "last shown target" in the controller and force `shouldShowMarker` to hide
  (return `false`) via internal state when an SMB hit consumes the tap, or use `Lock.X`/position
  reset, or explicitly clear via a `key` that forces the marker composable to reset.

## LineCartesianLayerMarkerTarget shape (from source)
- `LineCartesianLayerMarkerTarget.points: List<Point>`, each `Point(entry: LineCartesianLayerModel.Entry, canvasY, color)`.
  The tapped point's actual plotted y-value is available via `point.entry` for whichever series
  was hit — this repo's marker `ValueFormatter`s (`rememberBgValueMarker`,
  `rememberIobValueMarker`) currently ignore this and instead re-derive the value by manually
  interpolating the target's x against a separately-sorted raw data list (`bgReadings` /
  `processedIob`). That's equivalent for the "regular" series but silently falls back to
  extrapolation (clamped to the last real reading) if the tapped x lies outside the raw series'
  domain (e.g. tapping a future prediction point on the BG graph) — worth re-checking if
  prediction-line tap accuracy is ever reported as wrong.
