# Skin "Essential" and the manual TPO Boost — design

Date: 2026-11-16. Branch: `feature/skin-essential`. Source brief: "Brief d'implémentation — Skin AIMI
Essentiel" and the mock-up "Skin AIMI Essentiel" (French). Status: approved in chat, not yet built.

## 1. Goal

A third dashboard skin, next to Dashboard V2 and Glass, on Android and iOS. One hero on screen: the
glucose inside a living shape. The shape tells the situation (zone, trend, predicted trajectory) before
the number. Details come from the finger (scrub the chart, tap the shape), not from the screen. When the
trajectory goes into hyper, the shape offers one gesture: pull it up to start a short, capped insulin
boost; pull it down to cancel.

English name "Essential" (French brief: "Essentiel"). Preference value `essential`.

## 2. What it shows

- In the shape: BG (very large), trend arrow and delta.
- Under the shape: prediction at 60 min, IOB (can be negative), current temp basal (U/h), target.
- One quiet status line: insulin age, pump/patch age, sensor age, loop status, steps and heart rate.
- TIR with its delta, in a secondary place under the chart.
- Never: COB (AIMI does not use it), the insulin activity graph on the main screen, search, version,
  action tiles.

All values come from the same builders as Glass (`buildGlassUiState`, `buildGlassChartState`) and the same
view models (`GraphViewModel`, `ChipsViewModel`, `StatusViewModel`, `GlassHomeViewModel`), so at the same
moment Essential and Glass show the same numbers.

Prediction at 60 min: the value of the first available curve at now + 60 min (±5 min), in this order:
UAM, IOB, ZT (from `GraphViewModel.predictionsFlow`). AIMI is UAM-driven, so UAM first. No point near
+60 min: no prediction.

## 3. The living shape

A closed vector path drawn on a Compose `Canvas` from a few parameters: base radius, vertical stretch,
tilt, angularity, scale, colour. Each state is one parameter set; changes interpolate (300–600 ms,
ease-in-out). Breathing and the hypo pulse use an infinite transition. No images, no new dependency.

| State | When | Shape | Colour | Rhythm |
|---|---|---|---|---|
| STABLE | BG in [low, high], trend flat | near circle | green | slow breath (±2 %, ~4 s) |
| RISING | trend up | egg stretched up | amber | faster breath |
| FALLING | trend down | drop stretched down | orange | faster breath |
| HYPER | BG > high | swollen, scale > 1 | deep orange | slow, heavy |
| HYPO | BG < low | contracted, angular, scale < 1 | red | the only pulsing state (~1.2 s) |
| TRAJECTORY_HYPER | BG in range, prediction 60 > high | leans and stretches up, halo up | green → amber | rising tension |
| TRAJECTORY_HYPO | BG in range, prediction 60 < low | leans and stretches down | green → red | rising tension |
| NO_DATA | no BG younger than 10 min | grey, still, "—" | grey | none |

Low and high are the user's `OverviewLowMark` / `OverviewHighMark` (via `GraphViewModel.chartConfigFlow`),
never hard-coded. Hysteresis: a state is left only when its exit condition holds by a margin (5 mg/dL for
BG limits, trend must clearly change), so the shape does not flicker at a limit. Colour and geometry always
change together. No prediction: no trajectory states. The rules live in a pure function with tests.

## 4. The chart

6 h by default, 6/12/18/24 h selector. BG line coloured by zone, quiet target band, dotted prediction,
basal as steps at the bottom (actual delivered rate), SMB as dots on the curve sized by dose. Only hours
and the low/high marks as labels.

Scrub: finger down and slide → a cursor follows, a readout under the chart shows time, BG, basal decided
at that time (U/h), SMB delivered (U) or "no SMB". Release → back to "now" in ~250 ms, readout hides.
Keyboard: left/right move 5 min.

## 5. Gestures

- **Shape, vertical** — pull up past ~56 dp and release: start the Boost. Pull down past the same
  threshold and release while a Boost runs: cancel it. Below the threshold: elastic return, nothing
  happens. A gauge under the shape fills while pulling; light tick when the threshold is crossed, a
  confirmation vibration on start and on cancel (`LocalHapticFeedback`).
- **Shape, tap** — opens a bottom sheet: recent decisions (basal, SMB), insulin activity graph, detailed
  ages, explicit "Start boost" / "Cancel boost" buttons, and the reason when the Boost is not available.
  A tap never touches insulin.
- **Chart, horizontal** — scrub (section 4).
- The shape takes vertical drags, the chart horizontal drags, the sheet its own close drag. Touch targets
  ≥ 48 dp. Keyboard: up/down on the focused shape = start/cancel.

## 6. TPO Boost (the only part that changes insulin delivery)

A new TPO pack `MANUAL_BOOST`, started only by the user (gesture or button), never by the automatic
trigger engine.

- Changes, each from the user's own current value, each at most +20 %:
  - `DoubleKey.OApsAIMIHighBGMaxSMB` → `min(v × 1.20, key max)`
  - `DoubleKey.ApsMaxBasal` → `min(v × 1.20, key max, HardLimits.maxBasal())`
  - `DoubleKey.autodriveMaxBasal` → `min(v × 1.20, key max, HardLimits.maxBasal())`
  - Rounded down to 0.01, so rounding can never pass +20 %.
  - MaxSMB, max IOB and the target are not touched.
- Duration 60 min, then the existing revert (`TpoSessionManager`, `TpoRevertPolicy`): a value the user
  changed by hand during the Boost is kept.
- Priority 0, the lowest. Any automatic protective pack (post-hypo, poor sleep, exhausted) supersedes a
  running Boost: the Boost is reverted first, then the protection applies. Safety wins.
- Logged in the Advisor history like every TPO session.
- Not gated by the automatic TPO switch, no LLM confirmation: the full gesture (threshold crossed, then
  released) or the explicit button is the confirmation.
- Known effect: `ApsMaxBasal` is synced to client phones, so a follower also sees the raised value for the
  60 min.

### Guards (checked in the plugin, again at start, so the UI cannot bypass them)

The Boost can start only when all hold:
1. AIMI is the active APS plugin and the running mode is closed loop.
2. The last AIMI tick is younger than 10 min.
3. BG of that tick ≥ the profile target and ≥ 80 mg/dL; delta > −2 mg/dL per 5 min (not falling).
4. No low in the last 75 min (`minBgLookback75m` ≥ 70) and no `REBOUND_GUARD` tier.
5. No other TPO session is active or pending.
6. No Boost is already running (then the only offer is cancel).

The UI offers the gesture only in TRAJECTORY_HYPER, RISING above target, or HYPER, and only when the
plugin says the Boost is available. When it is not, the sheet shows the reason in one line.

## 7. Architecture

- `core/interfaces` (commonMain): `AimiBoostControl` — `state: StateFlow<AimiBoostState>`,
  `start(): AimiBoostStartResult`, `cancel(): Boolean`. `AimiBoostState` = available, reason when not,
  active, remaining minutes. Platform-neutral; the skin depends only on this.
- `plugins/aps` (commonMain): `TpoBoostController` implements it on top of `TpoOrchestrator` /
  `TpoSessionManager`; `TpoBoostEligibility` is the pure guard function; `TpoDeltaBuilder` gets the
  Boost plan; `TpoPackId.MANUAL_BOOST(priority = 0)`; the three keys join `TpoPreferenceKeys`;
  `startSession` takes the TTL.
- `ui` (commonMain), package `dashboard/essential`: `EssentialDashboardHome`, `EssentialShapeState`
  (pure classifier + hysteresis), `LivingShape`, `EssentialChart` + scrub, `EssentialDetailSheet`,
  `EssentialViewModel` (boost state + actions). Registered in `DashboardHomeSelection.ESSENTIAL`,
  `StringKey.GeneralDashboardSkin` and the `OverviewScreen` switch. English strings only in
  `values/strings.xml`.
- No platform code: Android and iOS run the same Compose. Desktop: if the Boost controller cannot be
  built there, it is excluded like Tidepool, and the skin shows no Boost offer.

## 8. Tests

- `EssentialShapeState`: all 8 states reachable, hysteresis at limits, no trajectory without prediction,
  user thresholds respected.
- `TpoBoostEligibility`: each guard blocks on its own.
- Boost plan: +20 % cap, key max, hard limit, rounding down, keys untouched when already at the cap.
- Boost lifecycle: start writes 3 keys, revert restores them, a protective pack supersedes the Boost.
- Scrub lookup: basal and SMB at a given time.
- Run on JVM, Android host and the iOS simulator; then the full CI suite.

## 9. Not verified by tests

The look and feel of the shape and the gestures on real phones, and the Boost on a real pump.
