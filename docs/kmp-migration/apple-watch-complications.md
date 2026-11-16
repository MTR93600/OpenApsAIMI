# Apple Watch complications (plan for later)

Status: **not started, nothing decided.** Written on 2026-11-16 to keep the research. Read
`widget-live-activity.md` first: this work builds on the same widget state.

## What we already have

- **Smart Stack on the watch: done.** The Live Activity declares `.supplementalActivityFamilies([.small])`
  in `AapsLiveActivityWidget`, so watchOS 11+ shows it in the Smart Stack (turn the crown). It is
  updated locally by the iPhone app, no APNs. It is not on the watch face itself.
- **Lock screen widgets** (`.accessoryCircular`, `.accessoryRectangular`, `.accessoryInline`) in
  `AapsHomeWidget`. These are the same WidgetKit families that watchOS uses for complications, so the
  views can be reused almost as they are.
- **Stale handling** that does not need a reload: the age is shown with `Text(date, style: .relative)`
  (it counts by itself), and the timeline has a second entry at `staleAt`, so the value is struck out
  on time even when nobody reloads the widget. This must be kept on the watch.

What is missing is a complication **on the watch face**. That needs code that runs on the watch.

## The real problem: getting fresh data to the watch

The App Group file (`AapsWidgetStore`) is not shared between the iPhone and the watch. The watch
needs its own copy of the state, and watchOS limits how often it can be woken:

- `WCSession.transferCurrentComplicationUserInfo` wakes the complication, but only about 50 times a
  day (`remainingComplicationUserInfoTransfers` tells how many are left). A CGM every 5 minutes is
  288 readings a day.
- `updateApplicationContext` / `transferUserInfo` have no such budget, but do not wake the
  complication. The value is fresh when the watch app is opened.
- `sendMessage` works only while the watch app is reachable (in the foreground).
- The watch also has its own WidgetKit reload budget. Exact numbers are not public.

On the iPhone, the home widget is already about 10 minutes behind on iOS 27 (reload budget, owner
deferred the fix). Expect the watch to be the same or worse.

## How Loop does it (LoopKit/Loop, branch `dev`)

- A full watchOS app in `WatchApp Extension/` (WatchKit UI: HUD, chart, carbs, bolus, overrides).
- Complications in `ComplicationController.swift` with **ClockKit** (`CLKComplicationDataSource`).
  ClockKit is deprecated since watchOS 9. We should use WidgetKit instead.
- Sending, in `Loop/Managers/WatchDataManager.swift`, function `sendWatchContext`:
  - Use `transferCurrentComplicationUserInfo` **only if** enough time has passed **or** glucose moved
    by more than `minTrendDrift` since the last complication update.
  - "Enough time" = time left until midnight / (transfers left + 1). The daily budget is spread over
    the day. If HealthKit knows the bedtime, it is spread until bedtime instead, so updates are more
    frequent while the user is awake.
  - Otherwise use `updateApplicationContext`.
- On the watch, `getTimelineEntries` adds future entries at "glucose becomes stale" and at each
  loop freshness step (fresh, aging, stale). The complication goes stale by itself.

Result: the Loop complication is updated about every 15 to 30 minutes, faster on big changes.

## Ways to do better than Loop

Ordered from most useful to least. None of them removes the limits of iOS. They use them better.

1. **Keep the age live (already done).** `Text(date, style: .relative)` and a timeline entry at
   `staleAt` cost no reload. The user always sees how old the value is, even if it is not new.
   This is the most important point for safety.
2. **Spread the budget and send on big changes** (the Loop method above). Cheap, proven.
   Possible extras for us:
   - Send right away when the state changes class: going low, going high, becoming stale, a new
     "carbs required". Spend the budget where it matters, not on a flat night.
   - Keep a small reserve for the evening (do not spend the whole budget by noon).
3. **The watch reads Nightscout itself.** watchOS apps can use the network on their own (Wi-Fi or
   LTE, or through the phone). This does not depend on the `WCSession` budget, and it keeps working
   when the phone app is frozen. Limits: the watch background refresh is also budgeted by watchOS,
   it costs watch battery, it needs a Nightscout URL and token stored on the watch (Keychain, never
   in code), and it shows what Nightscout has, which can be behind the phone.
4. **Widget push updates through APNs.** Apple added push updates for widgets with iOS 26 /
   watchOS 26 (to verify: API name, watchOS support, budget). A server sends a push, WidgetKit
   reloads. Problem for a DIY app: each builder would need an APNs key and a server that sends the
   push (for example Nightscout or a small service). That is a lot of setup per user. Live Activity
   push updates have the same problem.
5. **Use more of the Smart Stack.** It is already fed by local Live Activity updates on every new BG,
   with no `WCSession` budget. It is probably the freshest thing on the watch today. Limits: a Live
   Activity ends after about 8 hours (12 on the lock screen), and it cannot be restarted from the
   background without push-to-start (APNs again). Check how often it really updates on the watch
   before building complications.

Not recommended:

- **HealthKit as the transport.** HealthKit syncs from the phone to the watch slowly and without any
  timing promise.
- **CGM directly to the watch over Bluetooth.** Some vendor apps do it, but it means a second CGM
  driver on watchOS. Far too much work, and two devices fighting for one sensor.
- **Showing predicted values as if they were current**, to fill the gaps between reloads. A
  prediction must never look like a measured glucose.

## Proposed steps (when we pick this up)

1. **Measure first.** On a real iPhone + watch: how often does the Smart Stack Live Activity
   really update? If it is close to every 5 minutes, it may be enough for most users, and the
   rest of this plan can wait.
2. **watchOS targets.** A minimal watchOS app (it can be almost empty) and a watchOS widget
   extension with WidgetKit. New bundle IDs and provisioning profiles; check what the builders'
   signing (`Signing.xcconfig`) needs.
3. **Reuse the views.** Move the `accessory*` views and `ContentState` decoding into code shared by
   the iOS and watchOS extensions. One copy of the stale rule, not two.
4. **Transport, iPhone side (Swift).** A `WCSession` sender fed by the same `WidgetStateJson` the app
   already writes. Loop method: spread budget + send on big change + send on class change. Kotlin
   stays the source of the state; Swift only moves JSON (same pattern as `ConnectIqBridge`).
5. **Transport, watch side.** Store the last state, reload the timeline, keep the `staleAt` entry.
6. **Optional:** Nightscout fallback on the watch (step 3 of "Ways to do better").

## Open decisions

- Transport: `WCSession` only, or `WCSession` + Nightscout fallback on the watch?
- What the complication shows: glucose + arrow + age only, or also IOB / COB / loop state?
- Is a watch app with real screens wanted, or only the complication?
- **Bolus or carbs from the watch: out of scope.** Loop does it, but it is an insulin command. It
  needs the same care as on the phone (confirmation, limits, lost messages). Separate decision.

## Safety rules (apply to any option)

- The watch must judge the age itself, at the time it shows the value. It must never trust that
  the phone sent the latest value.
- A stale value: struck out, grey, warning sign, no arrow, no delta, no "carbs required", same as
  the iPhone widget.
- No file, an unreadable file or an unknown version shows "no value", never old numbers.
- All texts come from Kotlin, already translated. Nothing is joined into a sentence in Swift.
