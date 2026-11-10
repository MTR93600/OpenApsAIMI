# Custom Watchface in WFF — Status, Decisions & Working Plan

> **Note for this repository — read first**
>
> The Watch Face Format (WFF) code described here is now ported into this repository.
>
> It is still exploratory work (a proof of concept) with known limits. The main limit is that the
> Watch Face Push validator cannot run on Android: it needs `java.awt` and `javax.imageio`, which
> Android does not have and an app cannot provide. So the token that Watch Face Push requires is
> produced **at build time**, never at run time.
>
> The previous AAPS V4 watch face design was replaced by the new image based one. A copy of the old
> design is kept in `_docs/watchface_aapsv4_previous.xml`.

Living document for the topic **"Custom Watchface in WFF"**. Same role for this topic as
`CWF_ComplicationSlotsPrompt.md` has for the complication-slots topic.

**Division of labor between the `_docs` files**

| File | Holds |
|---|---|
| `Complication_Libraries.md` | **Library facts only.** Every claim carries `file:line` + version. Read it *before* reading any androidx / WFF library source. Append every new finding there. |
| `CWF_ComplicationSlotsPrompt.md` | Decisions/status of the **complication slots** feature on the code-based CWF. |
| **this file** | Decisions/status/plan of the **WFF port** of Custom Watchface. |
| `CWF_ComplicationSlotsRules.md` | Task rules that also apply here (see "Rules inherited"). |

> **Status: Option A in progress. POC 1 passed on device (2026-08-31) — a render-only
> `CustomWatchface` draws the user's zip correctly to a bitmap, so the architecture in §7d is
> confirmed on hardware. Next: POC 2, the image complication provider.**

---

## 1. Why this topic exists

- **Custom Watchface (CWF)** is the AAPS watch face users actually want: a user designs it
  themselves, ships it as a `.zip` (a `CustomWatchface.json` describing which view is visible,
  its `left/top/width/height` in a 400x400 design square, font, color, size, plus bitmap/vector
  images and `.ttf`/`.otf` fonts), sends it from the phone, and the watch re-skins itself
  immediately. All the intelligence is **code behind** (`CustomWatchface.kt`, ~117 kB) which
  rescales the 400x400 design space to the real screen, resolves dynamic values, draws the BG
  chart, and — since the last topic — hosts 5 real complication slots.
- Recent Google/Samsung watches no longer let a third-party **code-based** watch face run. The only
  face format left there is **Watch Face Format (WFF)**: a purely declarative XML document inside a
  code-free APK. Users who move to a new watch lose their CWF.
  **The block lives in the device's firmware build, not in the Wear OS version** — see A1 below.
- **Goal of this topic:** give those users the same Custom Watchface, delivered through WFF.

### Premise — answered (A1)

AAPS is not distributed through the Play Store (neither the phone nor the wear APK), so the
January 2026 Play Store policy never applied to us. The real blocker is **watch firmware**: Google
and Samsung removed the libraries that display code-based watch faces from recent watches and from
recent Wear OS.

- **Galaxy Watch 4/5/6** — libraries kept, even on the latest updates. Code-based CWF still works.
- **Galaxy Watch 7/8/9** — libraries removed. **WFF only.**

**It is the firmware build, not the Wear OS version.** The libraries that *select and bind* a
code-based watch face are still present in the firmware shipped for GW4/5/6 and were removed from
the firmware built for GW7 and later. Confirmed on the maintainer's own device: a **GW4 running Wear
OS 6.0 / One UI 8.0 Watch still shows the code-based CustomWatchface**, while a GW7/8/9 on *the
same* Wear OS 6 and One UI 8 does not. So "Wear OS 6 removed code watch faces" is wrong and must not
be repeated — the same OS version behaves differently depending on the firmware image.

**The removal happened in stages on GW7, which is informative about *what* was removed.** The first
GW7 firmware (One UI 7.0) blocked only the **selection UI**: a code-based watch face could not be
picked through the interface, but users could still select it with a dedicated `adb` command, and it
then ran and rendered normally. A GW7 firmware released about a year later closed that path too.

Two consequences:

- The block is squarely on the **selection/binding** side of the boundary, not in rendering or in
  any app-side library. That is consistent with, and strengthens, the A-2 argument in §7d: our render
  path never asks the system to bind a watch face.
- **Do not treat the `adb` selection trick as a fallback for users.** It is gone on current GW7
  firmware, and it was never a supportable answer for a non-technical audience.

That GW4 also **supports Watch Face Push** (Wear OS 6 = API 36), which makes it the ideal test
device for this topic: it can exercise the full WFF + Watch Face Push pipeline **and** run the
code-based CWF, so the two can be compared side by side on one watch.

This corrects the note in `CWF_ComplicationSlotsPrompt.md` ("a Play-Store restriction, not a
device-level block"): that was true about the *store policy*, but a device-level block exists as
well, on the newer hardware.

**Consequence:** the code-based CWF must keep working forever for GW4–6, and the WFF port is an
addition next to it, never a replacement.

### Observed failure mode on GW7 (user reports, previous topic)

On GW4-6, opening the CustomWatchface preferences from the AAPS wear app **forces a switch to
CustomWatchface**: the Samsung SysUI editor path activates the face, so a user sitting on a Samsung
face is moved onto CWF. Users on **GW7 reported this does nothing** - the watch stays on whatever
Samsung or WFF face was selected.

**What this is evidence of.** Activation through that path is a *system-side* decision: the system
binds `WatchFaceControlService` and only then does androidx build the face instance in our process.
So the GW7 failure sits on the system side of the boundary - the platform refuses to bind or
activate a code-based `WatchFaceService`. This is the device-level block of A1, with an observed
failure mode attached.

**What this is NOT evidence of.** It does not say whether *our own process* can construct and draw a
`CustomWatchface` object on its own initiative, because in the editor path our code never gets to
run. Relevant to the A-2 question in 7d - it makes A-2 more plausible (the block is on the far side
of the boundary) but it does not measure it. **Inference, not measurement.**

**The bigger consequence, beyond A-2.** On GW7+ the entire existing CWF *configuration* path is
dead: the complication picker, the long-press "Customize" flow and the AAPS-menu entry to the CWF
preferences all assume the system treats `CustomWatchface` as a real, selectable watch face. The
WFF port therefore cannot inherit the slot-configuration UX built in the previous topic. On a WFF
face the 5 user slots are edited through the **system's own WFF editor** instead, which does work on
those watches. Three follow-ups:

- The user slots likely get *simpler* - we declare them, the system picks providers.
- 7b Q1 becomes more important: `isCustomizable="FALSE"` on the bridge slots is what stops a user
  breaking the generated layout through that same editor.
- Everything else the CWF preference menu offers (per-slot visibility, complication type priority,
  the CWF-specific settings) needs a **new home** on GW7+. Open design item.

---

## 2. What already exists in this repo (verified by reading the files)

This is a much better starting point than "from scratch".

- **`wear/watchfacepush/`** — a real, working, code-free WFF face module ("AAPS V4"), built as its
  own APK per wear flavor, package `<wear app id>.watchfacepush.aapsv4`.
    - `template/watchface.xml` (646 lines) is the WFF document, exported from Watch Face Studio,
      with `AAPS_WEAR_APP_ID` placeholders substituted at build time by `GenerateWatchFaceResTask`
      so each slot's `DefaultProviderPolicy` points at the matching flavor's AAPS complications.
    - The document lives in the APK as **`res/raw/watchface.xml` — a plain-text file, not compiled
      AXML** (the Gradle task writes plain text straight into `raw/`). *(Important for §4 Option B.)*
    - The APK must contain **no code at all** (R8 with no entry points strips even the `R` class);
      `isShrinkResources = false` because the watch face *is* resources.
- **`wear/build.gradle.kts` → `EmbedWatchFaceTask`** — runs Google's offline validator
  (`com.google.android.wearable.watchface.validator:validator-push-cli:1.1.0-alpha01`, main class
  `com.google.android.wearable.watchface.validator.cli.DwfValidation`), scrapes
  `generated token: (\S+)` from its output, and embeds `aapsv4.apk` + `aapsv4_token.txt` into the
  wear app's assets under `watchfacepush/`.
- **`wear/.../watchfaces/WatchFacePushHelper.kt`** — installs / updates / activates that face at
  runtime through `androidx.wear.watchfacepush:watchfacepush:1.0.0`
  (`addWatchFace(fd, token)`, `updateWatchFace(slotId, fd, token)`, `setWatchFaceAsActive(slotId)`,
  `isWatchFaceActive`). Requires **API 36 (Wear OS 6)**. The token is bound to the **exact APK
  bytes**, so APK and token always travel together.
- **CWF side**: `CustomWatchface.kt` (sole reader of the CWF json — see rules),
  `watchfaces/utils/WatchFaceComplication.kt` (generic slot plumbing, already extracted and
  reusable), `shared/impl/weardata/{JsonKeys,ViewKeys,JsonKeyValues,ResFileMap,ZipWatchfaceFormat}.kt`,
  `core/interfaces/rx/weardata/{CwfData,ResData,ResFormat,CwfMetaDataKey}.kt`.
- **`ViewKeys`** is the full list of drawable elements of a CWF: background, 5 complications, chart,
  cover_chart, 4 free texts, 2 external data sets, every value field, time/hour/minute/second, date
  fields, cover_plate and the 3 hands.

### The single most important consequence

The earlier "WFF cannot host CWF" conclusion (recorded in `CWF_ComplicationSlotsPrompt.md`) is
correct only for the naive reading: translating all of `DynProvider`/`ValueMap` into declarative
XML. The way out is that **WFF does not have to host the logic — it only has to host the result.**
Our code keeps running, as a *complication data source inside the AAPS wear app*; WFF becomes the
display surface. Everything below is built on that idea.

---

## 3. Hard constraints (each still to verify — see §7 spikes)

| # | Constraint | Status |
|---|---|---|
| C1 | WFF documents are declarative: no code, no custom data source. The only channel from AAPS into a WFF face is a **complication**. | assumed proven |
| C2 | Watch Face Push needs a **validator token over the exact APK bytes**. A runtime-generated APK needs a runtime-generated token. The validator is a plain-Java fat jar (xerces + commons-cli), so it is *probably* offline-capable — **unverified**. | **S1** |
| C3 | Building an APK normally needs `aapt2` (native) to write `resources.arsc` — not available at runtime on a watch or phone. | **S2** |
| C4 | Complication updates are throttled by the system; a provider cannot drive a 1 Hz redraw. Seconds and hands must be drawn by WFF itself. | **S3** |
| C5 | Complication payloads cross a Binder (≈1 MB). A full-screen image must be a compressed `Icon` (PNG/WEBP), never a raw bitmap. | **S3** |
| C6 | Ambient / AOD: WFF switches variants (`<Variant mode="AMBIENT">`), but the *provider* does not know the ambient state. | **S4** |
| C7 | Maximum number of `ComplicationSlot`s in one WFF document, and whether a non-customizable slot still counts. | **S5** |
| C8 | Which WFF version the target watches support, and what that version can do (custom fonts, expressions, image filters). | **S6** |

---

## 4. Candidate architectures

All of them keep one rule: the CWF `.json` is interpreted by AAPS code, never by WFF.

### Option A — "One picture" (static WFF document)

A near-empty WFF document with a single full-screen `ComplicationSlot`
(`SMALL_IMAGE`/`PHOTO_IMAGE`, `x=0 y=0`, full width/height, `isCustomizable="FALSE"`,
`DefaultProviderPolicy` pointing at a new AAPS provider). That provider renders **100 % of the CWF
layout** into a bitmap with the existing `CustomWatchface` drawing code and publishes it as the
complication image.

- Maximum reuse; no APK generation; works with the existing static face APK; any CWF zip works the
  moment it arrives.
- Refresh rate (C4): no seconds, no smooth hands, the minute may even lag.
- AOD (C6): one bitmap cannot be both the interactive and the burn-in-safe image.
- Payload cost on every update (C5), and battery.
- **Best used as the compatibility fallback and as the first end-to-end proof.**

### Option B — "Generated document" (build the WFF from the zip and push a new APK)

Translate `CustomWatchface.json` into a WFF `watchface.xml` when the zip arrives, repackage it into
the face APK, re-token it, and call `updateWatchFace()`.

- Native quality: real clock, correct AOD, native slots, low power, no image over IPC.
- Needs the whole repackaging chain (S1 + S2).
- Anything genuinely code-driven (BG chart, `DynProvider` ranges, `dynPref`) still cannot be
  expressed declaratively, so those parts still need image complications.
- **Key trick to test (S2):** `res/raw/watchface.xml` is stored **uncompiled**. If we ship a
  skeleton APK that already declares a fixed pool of resource entries in `resources.arsc`
  (`res/drawable-nodpi/img00..N.png`, `res/font/fnt0..N.ttf`, …), we may be able to replace the
  *file contents* inside the zip without touching the arsc at all — no `aapt2` needed. Then:
  re-zip → `apksigner` (pure Java) → validator (pure Java) → push. If this holds, Option B is
  reachable. If it does not, Option B dies and Option C degrades to a static document.

### Option C — Hybrid (recommended target)

Layer the screen the way the user described:

```
z0  background              -> image complication (AAPS provider, slow refresh, rarely changes)
z1  user complication slots -> native WFF slots (1..5), geometry from the json
z2  foreground / data layer -> image complication (AAPS provider, about once a minute)
z3  chart / cover_chart     -> image complication (or folded into z2)
z4  time, hands, date       -> NATIVE WFF elements, generated from the json
```

- Everything that needs code stays in code, delivered as images.
- Everything that must be smooth or AOD-correct (time, hands, seconds) is native WFF.
- The 5 user complication slots stay real system slots, behaving exactly like today's CWF.
- The document must be generated (Option B machinery) because slot geometry and clock styling come
  from the user's zip. **If S2 fails**, a reduced Option C is still possible with a *static*
  document plus a fixed grid of slot positions.

### Re-ranking after A2

A2 removes the "static document" escape hatch. The options are no longer three peers:

- **A is the floor.** It is the only design that needs no runtime APK generation, so it is the only
  thing guaranteed to be buildable. It is also, on its own, not good enough for the target (no
  seconds, weak AOD).
- **B/C are the target, and both stand on S2.** If a face APK cannot be rebuilt at runtime, the
  target design is unreachable and we ship A, degraded, and say so.
- **A and C share the same provider and the same rendering path**, so building A first is not
  wasted work — it is the first half of C.

**Proposal:** run **S1 + S2 first** (they decide whether the target exists at all), then build
**A** as the end-to-end proof, then grow it into **C**.

---

## 5. Answers from the user, and what they force

### A1 — Which watches (see §1)

GW4–6 keep the code-based CWF; GW7/8/9 and newer Wear OS are WFF-only. Both paths must coexist for
a long time.

### A2 — Every existing zip must work, and zips are unknown at build time

> "It should work for all existing zip files. The zips inside assets only exist so the user is
> never shown an empty list. The zip list is dynamic and not known when we build the APK — a zip is
> shared on Discord, downloaded, sent to the watch, and it works."

**This is the decisive constraint of the whole topic.** The watch face must be built from a zip
that did not exist when the APK was compiled. Everything that could have been prepared at build
time is off the table.

Direct consequences:

- A **static** WFF document can never follow the geometry of an arbitrary zip. WFF has no runtime
  configuration channel from an app: the only inputs are complications, the clock, and
  user-editable configurations that only the user can change in the system editor.
- Therefore, anything beyond Option A **requires generating the WFF document (and its resources) at
  runtime**, and pushing a rebuilt face APK. Spike **S2** stops being a nice-to-have and becomes
  the **gate for the whole target design**.
### A2b — Resource names are NOT a closed set (correction)

An earlier draft of this file claimed the resource names in a zip were a closed set (`ResFileMap`).
**That was wrong.** Verified in code:

- `ZipWatchfaceFormat.loadCustomWatchface` puts **every** `.png/.jpg/.svg/.ttf/.otf` entry of the
  zip into `resData`. Known names are normalised through `ResFileMap`; **any other name is kept
  verbatim** (`resData[entryName.substringBeforeLast(".")] = …`). So `resDataMap` is keyed by
  arbitrary, user-chosen file names.
- **Dynamic images.** `CustomWatchface.DynProvider.getDrawableSteps` reads `image1`, `image2`, …
  `imageN` (and `invalidImage`) out of a `dynData` block; each value is a **free-text file name**
  looked up in `resDataMap`. This is how the background changes with the BG value. Count and names
  are both unbounded.
- **Per-view background images.** `viewJson.optString(JsonKeys.BACKGROUND.key)` is likewise a free
  file name resolved through `resDataMap`.
- **Fonts.** `FontMap.init()` builds `customFonts` from the 9 built-in keys **plus every `.ttf` /
  `.otf` file in the zip, keyed by its lower-cased file base name**. A zip shipping `myFont.ttf`
  makes `"font": "myFont"` valid in the json. Again unbounded.

So only a *part* of the images have static names (`ResFileMap`: `Background`, `CoverPlate`,
`HourHand`, the arrow set and their High/Low variants). Everything else is dynamic in **both name
and count**.

**What this does to the Option B / S2 idea.** The names themselves are not the obstacle: we write
the generated `watchface.xml` ourselves, so we can map any CWF name onto a pool entry
(`myFont` → `@font/fnt03`, `MyNightBackground` → `@drawable/img12`). The obstacle is **count**: a
pre-declared pool in `resources.arsc` is finite, so S2 must now also answer:

- How large must the pool be to cover real zips? (Measure the images/fonts count in the zips we
  have, and pick a pool with a wide margin — the cost is small: a 1×1 placeholder PNG is ~100 bytes,
  so a 256-entry image pool plus a 16-entry font pool costs well under 50 kB in the skeleton APK.)
- What happens when a zip exceeds the pool — refuse, or fall back to Option A for that zip?
- SVG has to be **rasterised at generation time** (WFF renders no SVG), which loses the
  resolution-independence the code-based CWF has today. At what size do we rasterise?
- Can a pool entry declared as `.png` hold JPG bytes, or must we transcode everything to PNG?

### A3 — One wear APK to install

> "We currently only install one wear APK. I want to keep one overall wear APK, not several."

Clarification needed (**Q-A3**, see §5.1): the face APK is *never installed by the user*. It is
embedded in the wear app's assets and pushed at runtime by `WatchFacePushHelper`. So "one wear APK
to install" already holds today, even though a second, code-free APK exists inside it. The open
point is different and needs a spike (**S8**): **how many Watch Face Push slots does one app
get?** If the answer is one, then the CWF-WFF face and the existing "AAPS V4" face compete for the
same slot and we must decide which one occupies it (or make one face serve both roles).

### A4 — Generation may run on the phone

> "No technical constraints on my side. On the phone we already partially decode the zip (global
> image, json for metadata, visible views) to show information about the selected watch face, and
> on the phone everything is managed inside the `CustomWatchface` file (design rule). Only a
> simplified zip is sent to the watch."

So the phone already parses the zip, already owns the CWF file as the single interpreter, and
already reduces the zip before sending it. Generating the WFF document on the phone fits the
existing shape. The design rule may have to be adjusted as a *result* of the chosen architecture —
that is an explicit decision to take later, not a licence to spread json parsing around now.

### A5 — Reduced parity only as a work-in-progress state

Partial results are fine while building. The **target** is full parity; a feature is only dropped
if we hit a proven roadblock (for example per-second updates), and it is then recorded here as a
roadblock, not as a design choice.

### 5.1 Still open

- **Q-A3** — Confirm the reading above: is a second embedded, code-free, never-user-installed face
  APK acceptable, given the user still installs exactly one wear APK? (Depends on **S8**.)
- **Q-A6** — Is it acceptable that the WFF face is **rebuilt and re-pushed every time the user
  sends a new zip** (a few seconds of work, and the face is replaced under the user)? Depends on
  **S7** (Watch Face Push rate limits and what the user sees during an update).

---

## 6. Rules inherited for this topic

- Root `CLAUDE.md` wins over everything. Simple school English. No code changes without explicit
  approval. No commit until asked.
- **Read `_docs/Complication_Libraries.md` before reading any watchface/WFF library source**, and
  say which sections were checked. **Append every new library finding there**, with `file:line` and
  version. WFF / `DeclarativeWatchFaceRuntime` facts belong there too.
- **`CustomWatchface` stays the sole reader and interpreter of the CWF json.** If a new component
  must produce a WFF document from that json, it becomes part of the CWF interpretation layer — it
  must not be a second, independent parser somewhere else. *(Design item: agree where the
  "json → WFF" translator lives before writing it.)*
- No user health data, no PII, no secrets in code, comments, commits or docs.
- Wear APKs: **build only**; the user signs and installs (installing from here would force a
  data-wiping uninstall).

---

## 7. Working plan / checklist

### Phase 0 — Understand and decide (no code)

- [x] Read `CWF_ComplicationSlotsPrompt.md`, `CWF_ComplicationSlotsRules.md`,
      `Complication_Libraries.md` (WFF section) and the existing `watchfacepush` module
- [x] Answer O1–O5 with the user → recorded as A1–A5 in §5
- [ ] Map the CWF drawing pipeline end to end: zip → `CwfData` → `ViewMap` → `onDraw`; identify
      what is resolution-independent and what could render into an offscreen `Bitmap` unchanged
- [ ] Pick the target option (proposal: **C**, gated by S1/S2, staged through **A**)

### Phase 1 — Research spikes (read-only; findings go to `Complication_Libraries.md`)

**S1 and S2 come first and gate everything else** (see §4 "Re-ranking after A2").

- [x] **S1** Token generation — **DONE, see 7a.** Offline: yes. Programmatic API: yes.
      **On Android: no** (needs `javax.imageio` / `java.awt`). Decision needed → 7c.
- [x] **S2** Runtime APK patching — **DONE, PROVEN, see 7a.** WFF text and drawable bytes can both
      be swapped, re-aligned, re-signed and re-tokenised with no `aapt2`.
- [ ] **S2b** Remaining A2b questions, deferred until 7c is decided: pool sizing against real zips,
      overflow behaviour, SVG rasterisation size, whether a `.png` pool entry may hold JPG bytes,
      and whether the watch actually *renders* a swapped drawable.
- [x] **S9** Bundled **images** — **ANSWERED, see 7a**: `<Image resource>` takes a drawable
      resource id, so a pool entry must exist in `resources.arsc`. The full WFF schema (versions
      1–5) ships inside the validator jar and is now our local reference.
- [ ] **S9b** Bundled **fonts** — still open. `<Font family>` is a free string with no resource
      semantics in the schema. Check `bitmapFontsElement.xsd` / `bitmapFontElement.xsd` (WFF also
      supports *bitmap* fonts, which may be the real answer for arbitrary user fonts) and any
      WFS-exported face that uses a custom font.
- [ ] **S8** How many Watch Face Push slots does one app get? If one, decide how the CWF-WFF face
      and the existing "AAPS V4" face share it (see A3).
- [ ] **S3** Complication refresh: real update cadence for our own provider
      (`ComplicationDataSourceUpdateRequester`), the practical maximum image size through the
      Binder, and whether `PHOTO_IMAGE` or `SMALL_IMAGE` is the better full-screen carrier
- [ ] **S4** Ambient / AOD in WFF: what `<Variant mode="AMBIENT">` can and cannot do; how to serve a
      burn-in-safe variant when the provider is ambient-blind (two slots? alpha? a second image?)
- [ ] **S5** Slot-count limit in one WFF document; cost of a non-customizable slot
- [ ] **S6** WFF feature inventory for the version our target watches support: expressions, custom
      fonts, image filters, `Transform`, `Condition`, clock elements — written up as a *capability
      table* we can map `ViewKeys`/`JsonKeys` onto
- [ ] **S7** Watch Face Push at runtime: rate limits on `updateWatchFace`, what the user sees while
      it happens, whether the face stays active across an update, and the permission story for
      activation

### Phase 2 — Proof of concept (Option A)

**Test device:** the maintainer's **GW4 on Wear OS 6.0 / One UI 8.0** — supports Watch Face Push
*and* still runs the code-based CWF, so it covers the whole pipeline and allows a side-by-side
comparison. No emulator needed. It cannot reproduce the GW7+ block itself; that risk is covered
instead by the inert-construction proof (§7d) and by `BgGraphComplication` already running on
GW7/8/9.

**POC ladder, cheapest first** (each on a side branch, build only — the maintainer signs and
installs):

- [x] **POC 1 — renderer, no watch face at all. PASSED on device 2026-08-31 (GW4 / Wear OS 6.0 /
      One UI 8.0).** A debug screen (`CwfRenderPreviewActivity`) builds a render-only
      `CustomWatchface`, renders the loaded zip to a bitmap and shows it. Result: **the user's own
      watch face rendered correctly**, from a zip that even contains complications. Three
      observations, all as predicted:
      - **The picture is static** (the second hand does not move) — correct: `renderToBitmap` draws
        one frame and nothing drives repaints. A live watch face gets that from the engine's update
        loop, which a render-only instance does not have. Refreshing is the *host's* job, which for
        Option A means the complication update cadence (S3).
      - **Tapping re-renders and the second hand moves to the new position** — so each render really
        re-reads the clock and the data; it is not a cached bitmap.
      - **Complications are absent** — expected, and it confirms §7f: slots exist only when the
        *system* binds the watch face and feeds them. It also confirms from observation that
        `WatchFaceComplications.syncGeometry()` safely no-ops when the slot manager is null, which
        until now was only reasoned from the source.

      **Measured on device.** 450x450, **~250-300 ms per warm render** (samples across two sessions:
      237, 242, 249, 262, 278, 305, 321, 398, 523 ms), with a *fresh* `CustomWatchface`
      per render - so that figure includes construction, Dagger injection, the repository read,
      inflation, measure/layout and draw, not just a redraw. Comfortable for a complication updating
      every minute or every five; too slow for a per-second repaint. Keeping one warm instance and
      only re-running measure/layout/draw would very likely cost a fraction of this, and is the
      obvious optimisation if a faster cadence ever becomes reachable - **not measured yet**.

      **Cold start is a different story: the first render in a fresh process took 6656 ms** - roughly
      20x a warm one - covering class loading, the Dagger graph, the first DataStore read and chart
      setup. This matters for the provider, because a Wear app process is killed often: the first
      complication update after a restart pays this. It still fits inside the ~20 s
      `onComplicationRequest` deadline, but not with much room on a slower device, and it argues for
      publishing something cached first and rendering off the critical path. **Design input for
      POC 2.**

      **Correctness verified from a screenshot**, not just from absence of errors: BG value with
      trend arrow, both deltas, reading age, battery percentage, and the full chart (history, basal,
      prediction points, target lines, hour labels). The analog hands read 09:23 against a log
      timestamp of 09:22:56, and the chart's own "now" label agreed - so both the data and the clock
      are read fresh at render time.

      **Complication placeholders - resolved, and it is not a cosmetic fix.** The first render showed
      the zip's complication areas as empty styled boxes. The maintainer pointed out why hiding them
      is the wrong framing: the `complicationN` views in `activity_custom.xml` are empty
      placeholders whose **visibility is the bridge** into the CWF design. A watch face can use
      `dynPref` so that switching a complication off (`Show complication N` = false in the CWF
      preferences) sets that placeholder to `GONE` and brings **other** views up in the same place.

      So a render-only instance must behave exactly as if every complication were disabled, through
      the existing mechanism. Implemented as a single accessor, `CustomWatchface.prefBoolean()`,
      which returns the real preference except that a render-only instance always reports the
      "Show complication N" toggles as **off** - which is simply true for an instance that has no
      slots, not a workaround.

      **Three readers had to agree**, or the two halves of the bridge break:
      - `ViewMap.prefVisibility()` - the placeholder's visibility. Alone: box hidden, replacements
        still hidden.
      - `buildDynPrefs()` - the dynPref cascade. Alone: replacements appear *behind* a box that is
        still there.
      - `checkPref()` - alone: a permanent mismatch, rebuilding dynPrefs on every render.

      **Verified on device 2026-08-31:** the three previously empty areas now show the watch face's
      alternative views (IOB and basal rate, day name and date, carbs and reservoir), with
      everything else still current. So the render path exercises the full `dynPref` cascade, not
      just static styling. Render time 321 ms, unchanged.

      **Conclusion: A-2 is validated on hardware.** Constructing `CustomWatchface` outside the
      service lifecycle, inflating `activity_custom.xml`, running `setDataFields`/`setColorDark` and
      drawing to a `Bitmap` all work. The plan can proceed to POC 2.
- [ ] **POC 2 — the `PHOTO_IMAGE` provider**, checked through a WFF face.
- [ ] **POC 3 — a minimal side-loadable WFF face** with one full-screen image slot. Note the
      existing `wear/watchfacepush` APK is **not** directly selectable — it declares no wallpaper
      service, because Watch Face Push installs it into a runtime slot instead. A side-loadable
      variant needs that service declaration added.
- [ ] **POC 4 — the Watch Face Push path** end to end.


- [ ] New complication provider in the wear app that renders the whole CWF into a bitmap
- [ ] Minimal WFF document with one full-screen image slot and a `DefaultProviderPolicy` to it
- [ ] Second face module (per O3), built and embedded like `watchfacepush`
- [ ] Install / activate through `WatchFacePushHelper` (extended to a second face)
- [ ] On-device check: does the picture appear, at what latency, and what does AOD look like

### Phase 3 — Document generation (Option B machinery)

- [ ] Decide where the translator lives (per §6) and fix its input/output contract
- [ ] `json → watchface.xml` translator for the native subset: clock, hands, date, slot geometry
- [ ] Runtime repackage + sign + token + push pipeline (depends on S1/S2)
- [ ] Resource pipeline for the zip's images and fonts (depends on S2)

### Phase 4 — Hybrid assembly (Option C)

- [ ] Layer split: which `ViewKeys` are native and which are image
- [ ] The 5 user complication slots as native WFF slots, geometry from the json
- [ ] AOD strategy implemented
- [ ] Parity matrix: every `ViewKeys`/`JsonKeys` entry marked native / image / unsupported

### Phase 5 — Delivery

- [ ] Phone-side UX (which face receives the zip, how the user switches)
- [ ] Migration and coexistence with the code-based CWF
- [ ] User-facing documentation (plain language, non-technical audience)
- [ ] Update this file and `Complication_Libraries.md`

---

## 7a. Spike results — S1, S2, S9 (2026-08-30)

Library-level detail is in `Complication_Libraries.md` → "Watch Face Push validator
(`validator-push-cli` 1.1.0-alpha01) and the WFF schema". Only the project consequences are here.

### S9 — how WFF references bundled resources: **partly answered**

The complete WFF grammar (467 `.xsd` files, format versions 1–5) ships **inside the validator jar**
at `watch_face_format_validation/docs.zip`. It is now our local reference for every WFF question.

- **Images: answered.** `<Image resource="…">` takes a *"Drawable id of image resource"* — a real
  resource id, so a bundled image **must** have an entry in `resources.arsc`. There is no
  file-path form. This confirms the pre-declared-pool design is the right shape, and that the pool
  is a hard capacity limit.
- **Fonts: not answered.** `<Font family="…">` is a required free string with no resource semantics
  in the schema at all. How a bundled `.ttf` is addressed stays open → **S9b**.

### S2 — runtime repackaging without `aapt2`: **PROVEN**

Done end to end on this project's own face APK:

1. `res/raw/watchface.xml` is stored **as plain text** in the built APK (the `res/xml/*` files
   beside it are compiled binary AXML) — so the WFF document is directly patchable.
2. Patched the WFF text → re-zipped entry-by-entry → `zipalign -p -f 4` → `apksigner sign` with the
   ordinary debug keystore → **validator passes all 10 checks and emits a new token**.
3. Replaced a drawable's bytes (37 504-byte PNG → an unrelated 339-byte PNG, same zip path, arsc
   untouched) → **also validates, new token**.

So the packaging chain needs only a zip writer, `zipalign`, `apksigner` and the validator. **No
`aapt2`.** Two caveats: AGP release builds **shorten resource file paths**
(`res/raw/watchface.xml` → `res/li.xml`), so a patcher must resolve real paths from the arsc or we
must disable shortening; and this proves the *validator* accepts the APK, not that the watch
*renders* a swapped drawable — that stays a device test.

### S1 — token generation: **offline yes, on Android NO**

- **Offline: confirmed.** No HTTP code anywhere in the validator (one `java.net.URL` reference,
  consistent with loading its own bundled schemas). Nothing contacts a server.
- **Clean programmatic API exists:** `DwfValidatorFactory.create().validate(apkFile, packageName)`
  → `ValidationResult.validationToken()`. No need to scrape CLI stdout. The token binds the APK
  bytes **and** the pushing app's package name.
- **Blocker: the validator cannot run on Android.** It bundles TwelveMonkeys `imageio-webp` and its
  check helpers use `java.awt.image.BufferedImage` and `javax.imageio.*` directly. Android has
  neither, and `java.*` is a protected namespace an app's dex cannot supply. The dependency is used
  to read image dimensions, so it most plausibly sits under the **mandatory** "Memory footprint
  validation" check. The jar is also Java 17 bytecode.
- `androidx.wear.watchfacepush` 1.0.0 ships **no** token generator — its KDoc just says *"Get it
  from the provided validation library"*.

### What this means

The **packaging** half of the target design is proven. The **token** half is blocked: the phone is
Android and the watch is Android, so neither can run the supplier of the one artefact
`WatchFacePush` demands. This is the decision point of the whole topic — options in §7c.

## 7c. Decision needed — how to obtain a token for a runtime-generated face

Not yet decided. Listed with the honest cost of each; **needs the user's call**.

1. **Port the validator to Android.** Provide Android-side replacements for the handful of
   `javax.imageio`/`java.awt` uses (they only read image dimensions, which Android's
   `BitmapFactory` with `inJustDecodeBounds` does natively) and repackage the result. Blocked by
   `java.*` being a protected namespace, so it means **rewriting the validator's bytecode** to
   point at shaded replacement classes. Heavy, fragile across validator versions, and legally
   worth checking (the jar is not published under an open licence the way the XSDs are).
2. **Compute the token ourselves.** The token is a digest over (APK bytes, package name). Deriving
   the algorithm means reverse-engineering Google's validation gate. Technically likely easy;
   **this is a policy question, not a technical one**, and must be the user's explicit decision,
   not mine. Note the validator's purpose is explicitly offline local generation, so this is not
   circumventing a server-side control — but it is still re-implementing a vendor gate.
3. **Give up runtime generation; ship Option A.** One static face APK, tokenised at build time,
   with the whole CWF drawn into a full-screen image complication. Fully within the rules, works
   with any zip, and costs the seconds hand, smooth AOD and battery.
4. **Hybrid fallback: a fixed library of pre-tokenised layouts.** Ship N pre-built face APKs
   (each already validated at build time) covering common geometries, and pick the closest at
   runtime. Contradicts A2 ("all zips work") unless combined with Option A for the rest.

**My recommendation:** proceed with **Option A now** (it is unblocked, and it is the shared first
half of every other option), while asking the user to decide between 1, 2 and 3 for the long term.
Option A is also the only one that certainly ships.

## 7d. Option A — structural analysis (2026-08-30)

### The drawing pipeline (Phase 0 item, now done)

`CustomWatchface` draws through an ordinary **Android View hierarchy**, not a hand-rolled canvas
renderer: `BaseWatchFace` inflates `activity_custom.xml` into `binding.mainLayout`, then does
`measure(specW, specH)` / `layout(0, 0, w, h)` / `draw(canvas)` (`BaseWatchFace.kt:383-398`).

**That is very good news for Option A:** a View hierarchy draws onto *any* `Canvas`, including one
backed by a `Bitmap`. No watch face engine is required to produce the picture. Nothing in the
drawing itself is tied to being a watch face.

### The one real obstacle

The drawing code lives inside a **`Service` subclass hierarchy** —
`CustomWatchface : BaseWatchFace : WatchFace : WatchFaceService` — about 155 kB across
`CustomWatchface.kt` (117 kB) and `BaseWatchFace.kt` (37 kB). A `ComplicationDataSourceService`
cannot simply instantiate `CustomWatchface`.

### But the codebase already supports a service-less instance

`BaseWatchFace.ensureInjected()` (`BaseWatchFace.kt:190-196`) exists precisely for instances that
**never get an `onCreate()`**. Its KDoc describes the case: *"Editor sessions and preview generation
run the watch face as a headless instance, which `androidx.wear.watchface` builds by reflection:
`newInstance()` plus an internal `setContext()` that calls `attachBaseContext`."* So a
`CustomWatchface` object living outside the normal service lifecycle is an **already-supported,
already-exercised** shape in this project, not a new hack.

### Three ways to build Option A's renderer

| | Approach | Cost | Risk to the working GW4-6 face |
|---|---|---|---|
| **A-1** | Extract a Context-based `CwfRenderer`; `CustomWatchface` delegates to it | Large refactor of ~155 kB | **High** - touches the face that works today |
| **A-2** | Add a "render to bitmap" entry point *on* `CustomWatchface`, and let the provider build an instance the same way the headless editor path already does | Small, additive | **Low** - existing paths untouched |
| **A-3** | Write a second, independent CWF json interpreter for WFF | Duplicates 117 kB | Violates the sole-interpreter rule - **rejected** |

**Recommendation: A-2 now, A-1 later if it proves worthwhile.** A-2 adds code instead of moving it,
keeps the GW4-6 face byte-for-byte unchanged, reuses every existing json/`ViewMap`/`DynProvider`
behaviour for free, and proves the whole Option A pipeline on a device quickly. If the concept
holds, the clean extraction (A-1) can follow with the pipeline already validated.

*Assumption to verify on device:* the androidx watch face **classes** ship inside our own APK, so
instantiating them on a GW7+ watch should work - what those watches removed is the system's ability
to *bind* a code-based watch face, not the library itself. Confirm before relying on it.

### A-2 risk resolved (2026-08-30) - proposed decision: **A-2**

Two facts closed the question that 7d left open.

**1. The provider-renders-a-bitmap pipeline is already proven on the target hardware.** User
confirms `BgGraphComplication` works on GW7/8/9 and is *today* the way owners of those watches see
the BG curve, through an image complication on a WFF face. So everything in Option A except "what
draws the bitmap" is production-proven on exactly the watches this topic targets.

**2. Constructing a `CustomWatchface` object is inert - source-backed, not inferred.**

- `androidx.wear.watchface.WatchFaceService` (watchface 1.2.1) has **exactly one instance field**,
  and it is `by lazy` (`WatchFaceService.kt:461`). Nothing executes at construction.
- Engines, the user style schema and the slot manager are built **only over system IPC**, and
  `onCreateEngine()` is `final override` (`WatchFaceService.kt:638`) - see "Engine lifecycle" in
  `Complication_Libraries.md`. A bare object therefore creates **no engine**.
- `WatchFace` / `BaseWatchFace` field initializers are primitives, `Rect()`, `WatchFaceTime()` and
  disposables.
- `CustomWatchface`: `complications = WatchFaceComplications(context = this, ...)` only stores the
  references and derives `slotStates` from a list of ints
  (`watchfaces/utils/WatchFaceComplication.kt:490-505`); `complicationStyle` stores a reference;
  `backgroundView` is `by lazy`.

Nothing touches the `Context` during construction, so a `CustomWatchface()` built inside the
provider process is a plain inert object. The GW7 block observed in section 1 sits on the system
side of the boundary and is not reached by this path.

**Refined A-2 shape** (drops the fragile part of the original sketch): do **not** use
`attachBaseContext` / the headless lifecycle. Instead the provider constructs a plain
`CustomWatchface()`, injects it directly through `androidInjector().inject(instance)` using the
provider's own Context, and a small mechanical pass replaces the `this`-as-Context uses inside
`CustomWatchface` (`ContextCompat.getColor(this, ...)`, `ImageView(this)`, `getString(...)`,
`resources`) with the already-injected `context` field. No `Service` lifecycle is touched at all.

**Known debt accepted with this choice**, to be written down rather than glossed over:

- The drawing stays welded to a `Service` subclass; if Option C ever unblocks, A-1 is still owed.
- `inflateLayout()` has side effects a render-only instance must not perform - it writes
  `key_last_selected_watchface`, sends `EventUpdateSelectedWatchface`, and stores the default
  watchfaces into the repository. A guard is required, so A-2 is not purely additive.
- A-1 was additionally judged heavier than first described: `BaseWatchFace` is shared with
  `DigitalStyleWatchface`, and `WatchFace` with `CircleWatchface`, so extracting the state layer
  puts two other working watch faces at risk - users who are not even the target of this topic.

### Other Option A decisions still to make

- **Complication type**: `PHOTO_IMAGE` (wire `LARGE_IMAGE`) or `SMALL_IMAGE`. `PHOTO_IMAGE` is
  meant for full-screen images and is rarely offered by other faces, which also helps the Q2
  "hiding" problem. WFF fills the `PartImage` rectangle the document declares, so either works.
- **Which face APK** hosts the full-screen slot - depends on **S8** (how many Watch Face Push slots
  one app gets). If only one, this competes with the existing "AAPS V4" face.
- **Refresh strategy** - S3, still open.
- **AOD** - S4, still open.

## 7b. The "bridge" complications — locking and hiding

Option C uses complications in **two very different roles**, and they must not be confused:

| Role | Slots | Who chooses the provider |
|---|---|---|
| **Bridge** (our plumbing): background image, foreground/data image, chart image | z0, z2, z3 | **Nobody.** Fixed to our own provider. The user must never see or change these. |
| **User complication slots** (today's CWF feature) | the 5 slots | The user, through the normal picker. Unchanged behaviour. |

Two questions were raised. Neither is fully answered yet, but both have a documented starting
point, and the honest status of each is recorded below.

### Q1 — Can we lock the bridge slots in the WFF document?

**Likely yes; the lever exists but its semantics are unverified.**

- WFF's `ComplicationSlot` element carries an **`isCustomizable`** attribute. Our own
  `wear/watchfacepush/template/watchface.xml` uses `isCustomizable="TRUE"` on its user slots, so the
  attribute is real and WFS emits it. `FALSE` is the obvious lever for a bridge slot. **What
  `FALSE` actually does (hidden from the editor? shown but not editable? still tappable?) is not
  verified** → spike **S10**.
- The androidx concept it most likely maps onto is `ComplicationSlot.fixedComplicationDataSource`
  (`ComplicationSlot.kt:394`, an immutable `val` fixed by the builder). Supporting evidence that
  this means "not user-editable": `renderHighlightLayer` (1237–1239) **returns early** for a
  `fixedComplicationDataSource` slot, i.e. such a slot is never drawn as an editable highlight
  target.
- A second, face-level lever: `wear/watchfacepush/src/main/res/xml/watch_face_info.xml` declares
  `<Editable value="true" />`. Setting it to `false` would disable editing of the whole face — too
  blunt for us, since the 5 user slots must stay editable, but worth knowing it exists.
- **Planned shape:** bridge slots → `isCustomizable="FALSE"` + `DefaultProviderPolicy` pointing at
  our provider; the 5 user slots → `isCustomizable="TRUE"`, exactly like today.

### Q2 — Can we hide the bridge providers from the picker of *other* watch faces?

**Uncertain, and the evidence currently leans negative.** There is a documented mechanism, but two
independent problems sit on it.

The mechanism (from `Complication_Libraries.md`, "Safe watch face trust gating"):

- **`METADATA_KEY_SAFE_WATCH_FACES`** = `android.support.wearable.complications.SAFE_WATCH_FACES` —
  the provider's own comma-separated allow-list of trusted watch faces, as flattened
  `ComponentName`s **or bare package names**.
- **`METADATA_KEY_SAFE_WATCH_FACE_SUPPORTED_TYPES`** —
  **overrides** `METADATA_KEY_SUPPORTED_TYPES` for safe watch faces. The theoretical trick would be:
  declare an empty/useless normal `SUPPORTED_TYPES` and the real type only in the safe list, so
  other faces find nothing usable and the provider effectively disappears from their pickers.

Problem 1 — **it is gated behind a privileged permission.**
`SAFE_WATCH_FACE_SUPPORTED_TYPES` requires `com.google.wear.permission.GET_IS_FOR_SAFE_WATCH_FACE`.
AAPS is not a system/privileged app, so this is probably not grantable to us. → spike **S11**.

Problem 2 — **an observed platform deviation says the allow-list may not even be consulted.**
`Complication_Libraries.md` records a Samsung Health provider that declares **no**
`SAFE_WATCH_FACES` key at all, yet the system still supplies `SAFE` (1) / `UNSAFE` (2), never
`UNKNOWN`. On that platform the safe/unsafe decision is made by **Samsung's WearServices by its own
criteria**, not from the provider's manifest. Whether a *declared* allow-list is honoured is a
different question that the recorded observation does not settle — but it is a real risk that our
list would simply be ignored.

Note also: the exemption *"a watch face in the same app package as the data source does not need to
be listed"* does **not** help us. The face APK is a different package
(`<wear app id>.watchfacepush.<face id>`), so it would have to be listed explicitly.

**Fallback if Q2 has no clean answer** (mitigation, not a block): declare the bridge providers with
only the type they actually need, ideally a rarely-offered one (`LARGE_IMAGE`/`PHOTO_IMAGE`). Most
slots on other faces would then not offer them at all, and a user who does pick one gets a picture
sized for a full watch face — visibly wrong, but harmless. Worth confirming that the AAPS user
complications that already exist today have the same exposure, so this is not a new class of
problem, only a new instance of it.

### New spikes from this section

- [ ] **S10** WFF `isCustomizable="FALSE"`: what exactly does it do to a slot (editor visibility,
      editability, tap behaviour)? Does it map to `fixedComplicationDataSource`?
- [ ] **S11** Can AAPS hold `com.google.wear.permission.GET_IS_FOR_SAFE_WATCH_FACE`? If not, is
      `SAFE_WATCH_FACES` alone (without the types override) worth anything to us?
- [ ] **S12** Is there any *other* documented way to keep a `ComplicationDataSourceService` out of
      the system picker while keeping it bindable? (Check before assuming the fallback above is the
      only option.)

---

## 7e. Complication geometry vs tap area in WFF (answers S5, and constrains Option A)

Raised by the user: on the code-based CWF, a complication's **drawn** position and its **tap** area
are set in different phases and can drift apart - load a zip that moves a slot and the tap region
must be re-synced or the user taps the wrong place. Does WFF have the same hazard?

**No - and for a reason that cuts both ways.** Schema facts are in `Complication_Libraries.md`
("`ComplicationSlot` in WFF - geometry, hit area and the hard limit of 8").

- Both are declared on the **same element**, literally: the slot's own `x`/`y`/`width`/`height` is
  where it draws, and a **required** `<BoundingShape>` child (`BoundingBox` / `BoundingRoundBox` /
  `BoundingOval` / `BoundingArc`) is the tap area.
- `ComplicationSlot` allows **no `Transform`** child, so geometry cannot be expression-driven, and
  `Variant`'s `mode` is restricted to the single value `AMBIENT`.

So the desync **cannot happen**: one declaration, consistent by construction, no "set early, updated
later" phase to keep in sync. That whole class of bug disappears.

**The price, and it is significant: a WFF slot cannot move at all.** Loading a new zip cannot
reposition a complication slot. The only way to change slot geometry is to edit the document and
re-install the face - i.e. the Option B/C machinery, which is token-blocked.

### Consequences

1. **Option A cannot honour per-zip complication slot positions.** This is a real functional gap
   against today's CWF, not a polish item. Choices for v1: fixed slot positions baked into the
   static document (documented to users as a WFF limitation), or no user slots at all in v1.
   **Open decision.**
2. **S5 answered: at most 8 `ComplicationSlot` elements per Scene.** Our Option C layering wants 3
   bridge slots (background, foreground/data, chart) plus the 5 user slots = **exactly 8, with zero
   headroom**. A 4th bridge slot would have to displace a user slot. Worth designing around now -
   e.g. folding the chart into the foreground image to keep one spare.
3. **Slots may only be direct children of `Scene`** - not inside `Group`, not inside `Condition`.
   This kills an idea worth recording so nobody re-invents it: pre-declaring several alternative
   slot layouts in a static document and switching between them with an expression driven by an AAPS
   complication value. `Condition`'s `_CompareChild` group does not admit `ComplicationSlot`, so
   there is no conditional-layout escape hatch for Option A.
4. **`<Launch target="…">` is allowed inside `Group`**, so a *generated* document could reproduce
   CWF's own non-complication tap zones (sgv → chart, main-menu tap). Not available to Option A's
   static document, and another argument for Option C eventually.

## 7f. Complications on a WFF face - what is blocked, and why it does not block v1

Two ideas were explored for keeping complication handling inside our own code instead of giving it
to the WFF document. Both are reasonable; both hit a specific platform gate. Recorded so they are
not re-invented, and kept **open** in case a workaround appears later.

### Idea 1 - subscribe to the complication and let it render itself into a CWF-defined area

Not "replace the complication", but: our code subscribes, and the complication's own renderer draws
into the rectangle the zip defines.

- **Rendering half: feasible.** `ComplicationDrawable` draws a `ComplicationData` into *any* bounds
  we give it - the class this project already knows well from the complication-slots topic. Drawing
  a complication into a CWF-defined rectangle inside our bitmap is not the problem.
- **Subscription half: blocked.** Complication data reaches only the watch face the *system* has
  bound to that slot. There is no consumer-side "subscribe" API, and third-party provider services
  cannot be bound by an ordinary app: every provider (including our own, see
  `wear/src/main/AndroidManifest.xml:174, 192, 210, 231`) is declared with
  `android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER"`, a
  permission held by Wear Services.
- **Nuance:** this is blocked only for *third-party* providers. AAPS's **own** complications need no
  system involvement - we can call our own code and draw the result anywhere. But then it is not a
  complication at all, just CWF drawing AAPS data, which the existing views already do. The value of
  the idea lies entirely in the third-party case, which is the blocked one.

### Idea 2 - read the last screen tap position from inside our own code

Android does not expose global touch coordinates to apps. The only mechanisms that observe touches
outside your own windows are an **AccessibilityService** (user must enable it explicitly; it can
read touches everywhere on the device - a bad privacy trade for a health app) or a **system overlay
window** (would intercept touches and break the watch face; overlay permission is restricted on Wear
OS). WFF itself has no tap-coordinate data source: its only tap concepts are `<Launch target>`
(static regions) and `TAP` as an event trigger for image sequences.

*Confidence: "no reasonable API exists", from the platform model - not an exhaustive search of every
Wear-specific API.*

The clean equivalent is `<Launch target="...">` regions in a **generated** document: real per-region
tap targets matching the zip, no permissions, no hacks. Option C again.

### Why none of this blocks v1 - decision on section 7e consequence 1

**Every CWF zip currently shared between AAPS users has NO complications.** Complication support is
a very recent feature whose code is not yet merged into `dev`. So the zips actually in circulation
- the ones users would want on a GW7/8/9 today - do not use complication slots at all.

**Decision: Option A v1 ships without complication slots.** That covers 100 % of the zips in
circulation, which is a large win on its own. Zips designed later with complications will not be
fully supported at first, and that is accepted. Complication support on WFF stays an **open item**,
to be revisited if a workaround appears (or when Option C becomes reachable).

This resolves the open decision left in section 7e ("fixed slot positions, or no user slots in v1"):
**no user slots in v1**.

### Incidental data point for S3

`BgGraphComplication` declares `SUPPORTED_TYPES = SMALL_IMAGE,LARGE_IMAGE` and
**`UPDATE_PERIOD_SECONDS = 300`** (`wear/src/main/AndroidManifest.xml:237-241`). So the
proven-on-GW7/8/9 graph refreshes on a 5-minute period - the first real figure for the refresh
cadence question.

## 7g. Explored and closed — "write our own watch face runtime"

Idea: if Google/Samsung removed the system libraries that host code-based watch faces, could AAPS
ship a replacement service that selects, hosts and draws the CustomWatchface itself?

**No — the role is privileged, and the obstacle is permission, not code.** A watch face is a
`WallpaperService` that the *system* selects and binds, and the component doing that is a system
app. `Complication_Libraries.md` records the real one from a device dump: package
`com.samsung.wear.watchface.runtime`, `codePath=/system/priv-app/DeclarativeWatchFaceRuntime`,
declaring `RuntimeControlService` behind **`com.google.android.wearable.permission.BIND_WATCH_FACE_CONTROL`**
and a `WallpaperService` under category
`com.google.android.wearable.watchface.category.RUNTIME_WATCH_FACE_SERVICE`. That is a
`/system/priv-app` role behind a signature/privileged permission. A sideloaded APK cannot claim it.

The staged-removal history (§1) confirms there is no seam left: while only the picker was blocked,
`adb` could still select a code face and it rendered; once later GW7 firmware closed that too, the
binding path itself was gone.

**The only app-level approximation is a Wear always-on Activity**, and it is not a watch face: it is
visible only while it is the foreground activity, it does not appear on wrist-raise from sleep,
always-on mode is intended for bounded sessions such as workouts and the system may exit it, and it
carries a continuous battery cost. It would leave the user with a watch that has stopped behaving
like a watch. **Rejected.**

**Pattern worth noting:** this is the third independent point where the platform reserves a role for
itself — alongside the Watch Face Push validation token (§7a/S1) and `BIND_COMPLICATION_PROVIDER`
(§7f). Each blocks a different "do it ourselves" route. WFF as the display surface, fed by our code,
remains the architecture.

## 7h. POC 2 result, the measured update cadence (S3) and ambient (S4)

**POC 2 passed on device 2026-08-31** (GW4, Wear OS 6.0 / One UI 8.0). `CwfImageComplication`
renders the Custom watch face and publishes it as an image; the AAPS V4 WFF face displays it in its
image slot (slot 14, the BG-graph area). The whole Option A chain now has device evidence:
**AAPS renders the CWF -> image complication -> a WFF face shows it.**

The picture appeared **stretched**, which is expected and confirms a recorded library fact rather
than a bug: WFF fills the rectangle its document declares and does not preserve aspect ratio. Slot
14 declares 420x210 while the render is 450x450. Our own document must declare a full-screen square
rectangle so the render maps 1:1 - nothing to change in the provider.

### S3 - measured update cadence: **the system ignores our request**

Manifest asked `UPDATE_PERIOD_SECONDS = 60`. Observed over 21 minutes of normal use, six requests:

| Request | Gap | Render time |
|---|---|---|
| 13:28:15 | - | 650 ms |
| 13:33:08 | 4m53s | 778 ms |
| 13:39:27 | 6m19s | 313 ms |
| 13:44:26 | 4m59s | 3769 ms |
| 13:46:01 | 1m35s | 320 ms |
| 13:49:00 | 2m59s | 368 ms |

So **roughly 1.5 to 6 minutes, mean about 4** - the 60 s request is not honoured.

**Consequence:** at this cadence the picture's **minutes** are wrong, not merely its seconds.

> **Correction (2026-08-31).** An earlier version of this section concluded "the clock must be drawn
> natively by WFF". **That conclusion is rejected by the design owner, and the reason is decisive:**
> the clock is *part of the user's design*. The zip owns its font, size, colour and position for a
> digital clock, and its hand images and their dynamic behaviour for an analog one. A native WFF
> clock cannot reproduce any of that, so replacing it would break the very thing this topic exists
> to preserve. **Everything stays in the image, clock included.**
>
> This does not weaken the measurement, it redirects it: instead of designing around a slow refresh,
> **the refresh has to be fast enough**. At least about once a minute, or the displayed time is
> simply wrong. That makes the untested **push** path (below) the gate for Option A as a whole, not
> an optimisation.
>
> **Seconds are the known casualty** and are treated separately: there is already a Show/Hide
> Seconds preference, which can be forced off for the WFF path if no solution is found. To be worked
> when that topic comes up - not now.

**Not yet measured, and it could change this:** only the *periodic* path was tested. A provider can
also **push** updates through `ComplicationDataSourceUpdateRequester`. Whether the system honours
pushes more generously than the periodic timer is unknown and cheap to test - **next step**, because
it decides how much WFF must draw natively.

### S4 - ambient: complications hidden in ambient is an authoring choice, not a platform limit

Observed: in AOD the complications vanished, leaving only the document's own digital time and BG.
**Cause found in our own template** - `wear/watchfacepush/template/watchface.xml` puts
`<Variant mode="AMBIENT" target="alpha" value="0" />` on its complication slots (5 occurrences,
including on `ComplicationSlot` itself at line 26). Watch Face Studio authored that. So a document
*can* keep complications visible in ambient; ours will decide for itself.

**Decision for the target design (maintainer's proposal, adopted):** hide the CWF image in ambient
and show a **minimal native WFF layer** instead. Reasons, each sufficient on its own:

- **Burn-in** - a large, bright, static image is exactly what AOD exists to avoid.
- **Staleness** - refresh is ~4 min interactive and presumably worse in ambient.
- **The provider is ambient-blind** - nothing tells it the watch entered ambient, so it cannot
  render a dimmed, thin-pixel variant on demand.

Considered and rejected: **two image slots**, one shown in interactive and one in ambient, each fed
by our provider, with the document switching them via `Variant`. It would work and needs no ambient
knowledge in the provider - the document chooses - but it costs 2 of the 8 available slots (§7e),
doubles rendering and IPC, and is still a photographic image in AOD. The native layer is better on
every count.

**Note the S3 correction above:** the clock is *not* moved to WFF. Ambient is a separate matter -
there the image may legitimately be hidden, and only then can a simplified native clock appear. See
§7j.

## 7i. Letting the user choose the ambient behaviour (requested)

Wish: the end user picks between a **simplified** ambient face and the **full CWF image**, the
latter possibly dimmed to save power. Schema facts below are from the bundled WFF v5 schema; the one
unverified item is marked.

**Dimming in ambient - trivial.** `<Variant mode="AMBIENT" target="alpha" value="128"/>` on the
slot. `alpha` is `_colorComponentType`, a union of `unsignedCharacterType` (0-255) and a
`_colorValuePreferences` enumeration of exactly **{0, 128, 255}** (`common/simpleTypes/colorType.xsd:22-32`)
- so 128 is the format's own idea of "dimmed", which suggests this is the intended AOD idiom.

**A user choice is expressible.** `BooleanConfiguration` and `ListConfiguration` are valid `Scene`
children (`sceneElement.xsd`), each holding options that may contain `PartElementGroup`, `Group`,
`Condition`, `AnalogClock` or `DigitalClock`
(`userConfiguration/booleanConfigurationElement.xsd`, `listConfigurationElement.xsd`). So a **native
simplified ambient layer can live inside an option** directly.

**But the image slot cannot.** `ComplicationSlot` is not among an option's allowed children -
consistent with slots being Scene-only (§7e). And slot `alpha` is a literal, not an expression.

**The likely way round, unverified:** `Variant`'s `value` **is** `arithmeticExpressionType`, and
`[CONFIGURATION.*]` is a documented expression data source (`CONFIGURATION.themeColor` appears in
`colorType.xsd:50` and `primitiveListTypes.xsd:150,169`). So
`<Variant mode="AMBIENT" target="alpha" value="<expression over a CONFIGURATION id>"/>` should let
the image's ambient visibility follow the user's choice. **Exact syntax not verified - test on
device before relying on it.**

### The real trade-off: where the setting lives

A WFF configuration is chosen in the **system watch face editor**, and there is no way for AAPS to
set it programmatically - the same wall as assigning a complication data source (§7b/§7f).

| Option | Setting lives in | Cost |
|---|---|---|
| WFF `BooleanConfiguration` / `ListConfiguration` | the watch face editor | none; but a second place to configure, separate from AAPS's existing `key_simplify_ui` (`off`/`ambient`/`charging`/`ambient_charging`, read by `SimpleUi.isEnabled`) |
| Second image slot holding an AAPS-rendered ambient variant | AAPS preferences | 1 of the 8 slots (§7e), an extra render and IPC, and still a photograph in AOD |

### Consequence to keep in view

If the full image is visible in ambient, **the clock inside it is stale** by up to six minutes
(§7h/S3). That is only acceptable once the clock is **excluded from the image and drawn natively**
by WFF on top - which S3 already forced independently. So "full image in ambient" and "native clock"
are not alternatives; the second is a precondition for the first being usable.

## 7k. Refresh-rate requirement (set by the maintainer, 2026-08-31)

Because the CWF image contains the clock (§7h correction), the image's refresh rate *is* the watch
face's clock rate. The requirement, in the maintainer's words and priority order:

| Rate | What it buys | Status |
|---|---|---|
| **1 s** | full seconds display | **target, ambitious** |
| **2 s or 5 s** | seconds shown, coarse - an acceptable workaround | fallback |
| **15-30 s** | no seconds, but IOB/BG and the rest stay fresh | fallback |
| **60 s** | **absolute maximum**, and only when phase-locked | hard ceiling |

**Phase alignment is part of the requirement, at every rate - not an afterthought.**

- At 60 s, refreshing at an arbitrary phase means the displayed minute is on average 30 s stale and
  up to 60 s wrong. It must land just after the minute boundary.
- At 2 s, the rendered second must fall on **0, 2, ... 58**, not 1, 3, ... 59. At 5 s, on
  **0, 5, ... 55**. A value off that grid looks wrong even when it is only a second out.
- Whether the render should aim slightly **before** or slightly **after** true time is an open
  decision the maintainer will take later.

**Nothing here is decided about seconds yet.** There is an existing Show/Hide Seconds preference,
which can be forced off for the WFF path if the achievable rate cannot support seconds - but that is
a fallback, not the plan.

**Consequences to keep in mind when measuring:**

- A render costs ~250-300 ms warm (§7h), so at a 1 s target it consumes about a third of the budget,
  and a fresh instance per render would have to become a warm one.
- Each update ships a 450x450 bitmap across a Binder. At 1 Hz that is a real power and IPC cost, and
  a reason the platform may refuse regardless of what we ask for.

## 7j. Ambient design - intended direction (separate topic, not now)

**Hard rule established 2026-08-31:** the CWF image contains **everything the zip designs**,
including the clock. Nothing in the zip's design is re-implemented natively in WFF. §7h's earlier
"native clock" conclusion is corrected there.

What WFF may legitimately **add** is a simplified digital clock **for ambient only**, and only when
the user accepts hiding the CWF image completely. The maintainer's proposed layering, recorded as
the direction to pursue:

```
z0  native WFF simplified digital clock   (always present in the document)
z1  full black background image           (hides z0)
z2  CWF image complication                (the watch face)
```

- **Interactive, and ambient-with-image:** z1 and z2 are opaque, so the native clock underneath is
  hidden and the user sees only their own design. Reducing z2's alpha to save power still hides the
  native clock, because the black background at z1 sits between them - which is exactly why z1 has
  to be there rather than relying on z2 alone.
- **Ambient with the image fully hidden (user's choice):** z2 *and* z1 become transparent, revealing
  the simplified native clock. Only in this mode does WFF draw any time itself.

So the user's ambient choice is between "my design, dimmed" and "a minimal readable clock" - never
between "my clock" and "WFF's clock".

**Open points for that topic when it starts:**

- Verify z-order in WFF: element order within `Scene` decides painting order, and
  `ComplicationSlot` is a `Scene`-only child (§7e), so the three layers above must be siblings in
  the right sequence. Not yet tested.
- Verify that a `ComplicationSlot`'s ambient alpha can follow a user configuration (§7i - the
  `Variant` value is an expression and `[CONFIGURATION.*]` is a documented data source, but the
  exact syntax is unverified).
- Decide whether the black layer is a bundled image or a `PartDraw` rectangle (cheaper, no resource).

## 7l. Push cadence measured, and the layered-image idea

### Push is not throttled - we are the bottleneck (measured 2026-08-31, GW4 / One UI 8.0)

A temporary loop pushed `requestUpdateAll()` on a 10 s grid. 16 pushes, all delivered.

- **Push -> delivery latency: 15-115 ms.** The system honours a push essentially immediately. The
  earlier 1m35s-6m19s figure was the **periodic** path only; push bypasses it entirely.
- **Render: 246 ms typical**, with severe outliers of **6011, 8028 and 9080 ms**.
- Our loop actually fired at irregular **7-56 s** gaps, never the 10 s asked for.

**Conclusion: the platform allows fast image updates; our render cost does not.** The render runs on
the main thread (it must, to inflate views), so a slow render blocks the handler that schedules the
next push - the irregularity is self-inflicted. The design question is therefore "how cheap can a
render be", which is ours to control, not "what will the system permit".

### The render outliers - bimodal, cause not established

Across both captures:

- periodic (POC 2): 650, 778, 313, **3769**, 320, 368 ms - 1 of 6 slow
- push: 285, **1851**, 499, 327, 657, 246, 258, 281, 423, 823, **9080**, 289, 296, **8028**, **6011**,
  314, 300 ms - 4 of 17 slow

So about **a quarter of renders exceed a second, worst case 6-9 s**, while the fast ones cluster
tightly at 250-500 ms. The distribution is **bimodal**, not a spread: identical work takes 280 ms or
9000 ms depending on conditions. That points at the thread being **starved** - process frozen, or the
CPU in a low-power state - rather than at variable drawing cost. The outliers also follow the longer
gaps between pushes, which fits.

**Cause not established.** Screen-state data was not captured, and the measurement loop has since been
reverted. Recorded as an open question, not a conclusion.

**Actionable regardless of cause.** Even on the benign reading - slow only while nobody is looking -
a **wrist raise** lands precisely in that state: the process has been idle and the first render after
waking is the slow one, at the exact moment the user is looking at the watch.

**Consequence: publish the cached image first.** If a render can take 9 s, the slot shows something
stale for 9 s anyway; serving the previous image immediately and pushing the fresh one when it is
ready is strictly better than making the system wait. This promotes the caching idea, deliberately
deferred during POC 2, into the right design - to be done as its own step. It remains confined to the
provider file (no impact on `CustomWatchface`), as noted when it was deferred.

### WFF-rotated hands - REJECTED (2026-08-31)

An earlier version of this section proposed letting WFF rotate a hand image supplied by the zip,
via `Transform` on `[SECOND]`, so a smooth second hand would cost no recurring updates. **The design
owner has rejected it**, for two reasons that are each sufficient:

1. **The hand image can be dynamic.** `DynData` may drive which image the hand uses, so "send it once"
   does not hold.
2. **The rotation centre may not be the face centre.** Already established below; rotating a full-face
   image about the centre cannot reproduce an off-centre pivot.

The schema facts gathered for it remain true and are kept below for reference (`Transform` is allowed
on any Part, `[SECOND]`/`[SECOND_MILLISECOND]` exist as data sources) - they may be useful elsewhere -
but **the approach is not the plan**. Superseded by the split-rendering design.

### The layered-image idea (maintainer's proposal) - and a better version of it

Proposal: instead of re-rendering everything every second, send **one full image without seconds**
every minute, plus **one small fast image** carrying only the second hand or digital seconds, and let
WFF overlay them. Merging two images is cheaper than recomputing the whole face.

**Schema check says this works, and that the fast layer can be eliminated entirely for analog hands.**
`Transform` is permitted on **any Part** (`5/group/part/abstractPartType.xsd:42`), its `target` is a
free string and its `value` is an `arithmeticExpressionType`; WFF's own data sources include `SECOND`
and `SECOND_MILLISECOND` (`5/common/simpleTypes/sourceType.xsd`). So a `PartImage` bound to
`[COMPLICATION.SMALL_IMAGE]` can be **rotated by WFF itself**:

```xml
<PartImage ...>              <!-- pivot at the face centre -->
  <Transform target="angle" value="[SECOND] * 6" />
  <Image resource="[COMPLICATION.SMALL_IMAGE]" />
</PartImage>
```

The hand image is sent **once**; WFF animates the rotation every frame. **No recurring traffic for a
smooth second hand** - strictly better than pushing a second-hand image every second. The same
applies to the hour and minute hands, which means the slow image need not carry hands at all and can
then refresh only when *data* changes.

| Layer | Content | Refresh |
|---|---|---|
| data image | everything except the hands | when data changes (~5 min) |
| hand images (x3) | one transparent full-screen PNG each, hand at 12 o'clock | **once**, only when the zip changes |
| rotation | `Transform` on `[SECOND]` / `[MINUTE]` / `[HOUR]` | every frame, by WFF |

**Caveats, none fatal but all real:**

- **Digital seconds do not rotate.** Digits change value, not angle. Either WFF draws `[SECOND]` as
  native text - which cannot match the zip's font, colour and position in a *static* document - or we
  push a small digits image, the original form of the proposal, which stays cheap.
- **Pivot — checked in code, and it mostly works.** Hands are declared **400x400 px, the full design
  square** (`res/layout/activity_custom.xml:913-915`), and `View.rotation` pivots about the view's own
  centre - which for a full-square hand image *is* the face centre. So WFF rotating a full-face image
  about the centre reproduces the default exactly.
  **However** hands are ordinary `ViewMap` entries, so `customizeViewCommon` applies the zip's
  `width`/`height`/`leftmargin`/`topmargin`: a zip **can** resize or move a hand, which moves its
  pivot. And `ROTATIONOFFSET` via `DynProvider` can add a **data-driven rotation offset** on top of
  the time rotation.
  **Do not treat the default as the norm** (maintainer's warning): a zip may well shift the hand, and
  then the pivot is genuinely off-centre. Rotating a full-face image about the face centre does not
  reproduce an off-centre pivot - it carries the hand *around* the centre instead of rotating it about
  its own point. That is geometrically wrong, not merely mis-scaled, and no static document can fix
  it: WFF would need the real `pivotX`/`pivotY`, which means a generated document.

  **Resolution - decide per zip at runtime, since our code reads the geometry:**

  | Zip's hand geometry | What the provider sends |
  |---|---|
  | pivot at the face centre | the hand image to the rotated slot; the hand is **omitted** from the data image |
  | pivot shifted | **nothing** to the rotated slot; the hand is **included** in the data image |

  In the second case the hand updates at data rate, i.e. effectively no working seconds - the honest
  fallback rather than a wrong picture. One slot layout serves both, so a static document still works.

  Note why the obvious alternative is unavailable: a static document **cannot** switch slots on and
  off. `ComplicationSlot` cannot sit inside a `Condition` (§7e), its `alpha` is a literal rather than
  an expression (§7i), and `Variant` fires only for `AMBIENT`. So "declare both and hide one" does not
  exist - the provider withholding data is the mechanism.
- **A dynamically chosen hand image is not a problem.** `DynProvider` can override the hand drawable,
  but that selection changes at **data rate** (~5 min), not per second - so the image is simply
  re-sent when its driving value changes. A dynamic rotation *offset* can be baked into the rendered
  image's orientation, since rotations compose.
- **Slot budget:** data + 3 hands = 4 of the 8 slots (§7e), leaving 4.
- Needs a render mode that draws **one view only**, which the existing `isRenderOnly` machinery makes
  cheap.
- This does **not** violate the §7h rule that the zip owns the design: the hand *images* still come
  from the zip, rendered by our code. WFF only rotates them - it draws nothing of its own.

## 7n. Split rendering - the adopted design (2026-08-31)

Replaces the rejected WFF-rotation idea. It attacks **render cost**, which the measurements show is
the real bottleneck (§7l: push is delivered in 15-115 ms, renders take 250 ms-9 s).

**Two full-screen images in two stacked slots, refreshed at different rates:**

| Block | `ViewKeys` range | Refresh | Why |
|---|---|---|---|
| 1 | `BACKGROUND` .. `STATUS` | slow (~30 s, to be tuned) | holds the background and most images, including the **chart** - the expensive part, and the part that changes slowly |
| 2 | `TIME` .. `SECOND_HAND` | fast | clock text, date fields, `COVER_PLATE` and the hands - what has to keep up with the clock |

WFF overlays them: two `ComplicationSlot`s, block 1 first in document order so block 2 paints on top.
Both are full-screen with identical geometry, so they register exactly. This also satisfies the
z-order constraint of §7m by construction, since the cut is a boundary in `ViewKeys` paint order.

**Why it should be much cheaper.** Block 2 excludes the chart and the background images, which are
the costly parts. With a retained (warm) instance there is no construction, no injection, no
inflation and no re-inflate between ticks - only drawing.

**Implementation sketch** (not built yet):

- Keep one warm `CustomWatchface`; render both blocks from it.
- To draw a subset, set the other block's views to **`INVISIBLE`, not `GONE`**: `INVISIBLE` skips
  drawing without triggering a re-layout, so geometry stays valid and measure/layout can be skipped.
- Block 2's bitmap must be **transparent** outside its own views so block 1 shows through.
- Two slots of the 8 (§7e).

### Measured 2026-08-31 (GW4, warm instance, foreground activity)

| Render | Time |
|---|---|
| **ALL** | 80-108 ms (median ~89) |
| **LOWER** (`BACKGROUND`..`STATUS`) | **21-24 ms** |
| **UPPER** (`TIME`..`SECOND_HAND`) | **63-74 ms** |

Compare a **fresh instance per render**: 250-300 ms (§7h).

**Finding 1 - the warm instance is the real win, about 3x.** Construction, Dagger injection and
inflation were most of the cost. Keeping one instance and re-drawing takes the full face from
~275 ms to ~90 ms.

**Finding 2 - measured against two zips, the split earns its place: it bounds the fast path.**

| | light zip | heavy zip (Steampunk) |
|---|---|---|
| ALL | ~90 ms | **136 ms** |
| LOWER | ~22 ms | **96 ms** |
| UPPER | ~70 ms | **42 ms** |

A first measurement against the light zip alone suggested the split was pointless (70 vs 90 ms). The
maintainer supplied a heavy zip, which reversed the reading - and the reason matters more than the
numbers:

- **LOWER swings ~4x with zip weight** (22 -> 96 ms). That is where backgrounds, images and the chart
  live, and it is exactly what a heavy design makes expensive.
- **UPPER stays in a narrow 42-70 ms band** across both, because it holds a bounded set of views. It
  is not even monotonic with zip weight - the heavy zip's upper block is *cheaper*, since its clock is
  a small dial while the light zip has three full-size rotated hands.

**So the value of the split is predictability, not an average saving: the per-second cost stops
depending on how heavy a user's design is.** At 1 Hz the fast path is a **4-7% duty cycle**, against
9-14% for full renders.

**Why these two zips differ, from the maintainer** (it makes the numbers a model rather than two data
points): the heavy zip has **many more images** - hence LOWER at 96 ms - but **no second hand and very
small hour and minute hands**, hence UPPER at only 42 ms. It does carry a **full-screen cover plate**,
but a **static** one.

**The lower block is not static either.** In that same zip the **background is a full-screen image
rotated by `DynData` according to the BG value**, so LOWER changes whenever BG changes and **cannot be
cached as "render once per zip"**. A refresh around **30 s** is the right order: BG arrives roughly
every 5 minutes, and 30 s keeps the slow block's other fields responsive without pretending the layer
is fixed. Rotating a full-screen bitmap is also part of why LOWER costs 96 ms - the same work that
made the light zip's three rotated hands expensive in *its* upper block.

That cover-plate detail points at a further optimisation, **not to act on now**: the cover plate is redrawn
on every fast tick although it never changes. A finer split by *change frequency* rather than by a
single `ViewKeys` cut would put it in a slow layer - but z-order constrains that, since
`COVER_PLATE` sits **above** the data and **below** the hands, and the digital `SECOND` text sits
*below* the cover plate. So a three-layer split (data / cover plate + clock text / hands) is
conceivable for analog designs and awkward for digital ones. Belongs with the layering discussion
parked in §7m.

*(A note on the earlier figures: the first warm measurements were taken before `refreshRenderData()`
re-applied data to the views, so they timed a redraw of stale content. The corrected build reloads the
data, re-reads the zip via `setColorDark()` and runs `setDataFields()` on every warm render - and the
heavy-zip numbers barely moved, so that whole refresh costs only a few ms. The light-zip figures
therefore remain a fair baseline.)*

**Caveat, and it is the main open risk:** measured in a **foreground activity with the screen on and
the CPU boosted**. A provider rendering with the screen off is precisely the condition suspected of
producing the 6-9 s outliers above. **The same measurement must be repeated from the provider, in
real conditions, before relying on any of these numbers.**

**Open questions:**

- Do these timings survive real provider conditions (screen off, idle process)? **The deciding test.**
- Keeping an instance warm in the provider: when to discard it (new zip, preference change) and what
  it costs in memory.
- If the split is kept anyway: whether both blocks can share one measure/layout pass.
- Whether the split point should be fixed at `STATUS`/`TIME` or derived per zip (§7m).

## 7m. Layer splitting - parked, to decide when we get there

Raised by the maintainer 2026-08-31. **Not to be solved now**, but recorded because it constrains any
design that splits the render across several complication slots.

**The constraint.** In WFF, painting order is document order, and `ComplicationSlot` may only be a
direct child of `Scene` (§7e). So the slot sequence in the document **is** the z-order. If we split
the CWF render into several images, the slots must appear in the same order as `ViewKeys`, which is
declared in paint order - background first, `COVER_PLATE` and the three hands last.

**Why it matters concretely.** A zip may place a **small analog dial off-centre** - say at the top,
with other information filling the space below - and a "double presentation" face shows analog hands
**in front of** the digital time. Get the split wrong and the hands paint underneath, or a
`COVER_PLATE` lands on the wrong side of them.

**Maintainer's proposed cut**, as a starting point for that discussion:

| Block | `ViewKeys` range | Character |
|---|---|---|
| 1 | `BACKGROUND` .. `STATUS` | most of the images live here |
| 2 | `TIME` .. `SECOND_HAND` | clock text, date fields, `COVER_PLATE`, the hands, and possibly some images |

**Open questions for that topic:**

- Each layer costs one of the **8** slots (§7e), and the hand-rotation design (§7l) already wants up
  to 3. Budget the split against that.
- Block 2 in the cut above still contains data-driven views (`SGV`, `LOOP`, `TIMESTAMP`), so it is not
  a pure "clock layer" and cannot be treated as static.
- Whether the split should be fixed, or derived per zip from which views are actually visible.

## 7o. S8 answered - one Watch Face Push slot per app, and what it forces

**Measured 2026-09-01 on a GW4 (Wear OS 6.0 / One UI 8.0):**

```
WatchFacePush: slots used=1 remaining=0 packages=info.nightscout.androidaps.watchfacepush.aapsv4
```

**An app gets exactly one Watch Face Push slot.** The existing "AAPS V4" face already occupies it,
so **the CWF face cannot be installed alongside it** - only `updateWatchFace()` on the same slot, or
`removeWatchFace()` to free it.

This settles the long-open **Q-A3** ("is a second embedded face APK acceptable?"): the question is
moot. Not because a second APK is unacceptable, but because the platform will not host two pushed
faces from one app.

### The choice it forces (for the maintainer)

| | Option | Consequence |
|---|---|---|
| **A** | **Replace** AAPS V4 with the CWF face | Simplest. Users lose the hand-authored AAPS V4 design, but gain their own zip - which is the point of the topic. |
| **B** | **Let the user choose** which face occupies the slot | A preference plus install/remove plumbing on top of `WatchFacePushHelper`. Keeps AAPS V4 for users who prefer it. |
| **C** | **One document serving both** - the CWF image, falling back to an AAPS V4-style layout when no zip is loaded | One face, no choice to make, but the document has to carry both designs and `ComplicationSlot` cannot be switched on and off (§7e/§7i), so the fallback would have to be built from the image itself. |

**Not decided.** B looks closest to how the rest of AAPS behaves (the user picks), but it is also the
most plumbing. Worth noting the slot is a *runtime* resource: switching faces means removing one and
adding the other, so the transition needs care - a failed add after a successful remove would leave
the user with no AAPS face at all.

### Also settled: the POC 3 document validates

A document with **two stacked full-screen `SMALL_IMAGE` slots** (lower declared first so the upper
paints on top), both `isCustomizable="FALSE"`, each with its own `DefaultProviderPolicy`, **passes
the WFF v1 validator**. Three things that were open are now confirmed:

- **No clock element is required** - a document that is only complication slots is valid.
- Two full-screen slots at identical geometry are accepted.
- `isCustomizable="FALSE"` is accepted (the §7b Q1 lever).

Validated with `WatchFaceXmlValidator` directly, no APK build - see `Complication_Libraries.md`.

## 7p. The provider measured in real conditions - the foreground numbers were optimistic

Every render figure up to this point came from a **foreground activity with the screen on**. This is
the same warm instance measured from **inside the provider**, screen mostly off, process idle
between requests (GW4, Wear OS 6.0 / One UI 8.0, 2026-08-31/09-01):

| Time | Render | Note |
|---|---|---|
| 23:52:06 | 175 ms | includes building the warm instance |
| 23:58:10 | 1352 ms | right after a reinstall - cold process |
| 23:58:16 | 943 ms | still settling |
| 00:03:19 | **235 ms** | steady state |
| 00:09:07 | **230 ms** | steady state |
| 00:14:21 | **3220 ms** | outlier |
| 00:14:29 | 533 ms | |

**Steady state in the provider is ~230 ms, about 2.5x the ~90 ms the same code showed in a
foreground activity.** The foreground figures were optimistic - the CPU is boosted there and the
process is not idle. Any future measurement of render cost must be taken from the provider, not from
the debug activity.

**The stalls are real in provider conditions too** (3220 ms here). Still unexplained, and now
observed in the environment that actually matters.

### What it changes

Nothing about the architecture, but it sharpens the case for the split (§7n):

| At 1 Hz | Foreground estimate | Provider reality (x2.5) |
|---|---|---|
| full face | 9-14% duty | **~23%** |
| upper half only | 4-7% duty | **~10-17%** |

A full-face render every second is uncomfortable at 23%; the upper half alone is the difference
between viable and not. So the split is not an optimisation to consider later - it is what makes a
per-second clock affordable at all.

**Caveats:** four usable steady-state samples, and the zip in use during the window is not certain
(the maintainer changed zips around then). Worth re-taking per zip once POC 3 is on the wrist, where
LOWER and UPPER can be measured separately in provider conditions.

## 7r. Ambient still shows the second hand for ~30 s - open

Measured behaviour after the ambient work (2026-09-01, GW4). Switching to ambient leaves the second
hand drawn, frozen, for **about 30 seconds** before the picture catches up.

It has improved through three rounds - it used to persist for most of the ambient period - and the
push is now issued **immediately** from the display listener, bypassing the coalescing loop. So the
remaining delay is most likely **the system delivering complication updates slowly while dozing**,
not our timing. Not confirmed: the watch was asleep and unreachable when the logs were to be read.

**Measured 2026-09-01 12:37 - the render is prompt, so it is not our timing:**

```
12:37:45.953  refresh all (ambient changed)
12:37:46.216  rendered UPPER 450x450 in 155 ms      <- 263 ms after the transition
```

So a correct, seconds-free image is produced within a third of a second of entering ambient, and the
old one still shows for about 30 s. The remaining explanation is that **the WFF face repaints only
about once a minute while in ambient**, so a freshly delivered complication image waits for the
runtime's next ambient tick. Half of a 60 s period averages the ~30 s observed.

**Consequence: pushing cannot fix this, and §7j stops being optional.** `<Variant mode="AMBIENT">` is
applied by the runtime *at the mode switch*, with no new complication data required - so letting WFF
hide or dim the image in ambient, and revealing a minimal native layer beneath, is the only approach
that reacts at the moment the watch dozes. Re-rendering a seconds-free variant and hoping it is shown
is a dead end.

*Still inferred rather than proven:* the once-a-minute ambient repaint is deduced from the delay, not
observed directly. A way to confirm would be to change something else visible in the image at a known
moment while in ambient and time how long it takes to appear.

## 7t. The app cannot update the picture in ambient at all - so §7j is mandatory

**Observed 2026-09-01:** in ambient the face stayed frozen for about two minutes across 15:30-15:31,
then jumped straight to 15:33.

That is not a repaint-phase problem. `dumpsys display` reports **`DOZE_SUSPEND`** in ambient: the
display refreshes itself while the CPU sleeps, and the app process is frozen. A coroutine `delay()`
does not fire while the process is suspended, so the per-minute clock loop simply does not run - and
when the process is later unfrozen it catches up in a jump, which is exactly the behaviour seen.
adb over Wi-Fi dies with the process too, which is why the repaint phase could not be sampled.

**Consequence: no app-driven refresh can keep the time right in ambient.** Push delivery is instant
while awake (15-115 ms, §7l) and irrelevant while asleep, because nothing of ours is running to issue
it. Pushing harder, aligning phases, or rendering ahead cannot fix a process that is not executing.

**So the layered ambient design of §7j stops being one option among several and becomes the only one
that can work.** The WFF runtime keeps drawing while our process sleeps, so in ambient the time has to
come from the document:

- **active**: the image carries the user's whole design, hands and seconds included - unchanged, and
  the zip still owns the clock;
- **ambient**: the image is hidden or dimmed, and a minimal **native** WFF clock beneath it shows the
  time, updated by the runtime with no app involvement.

This does not contradict the §7h rule that the zip owns its clock design. That rule governs the
active face. In ambient the choice is between a native clock and a **wrong** one, and the maintainer
already proposed the native layer for exactly this case.

**What this retires:**

- the ambient refresh-rate logic in `CwfComplicationUpdater` becomes pointless for correctness - it
  can stay to refresh opportunistically when the process happens to be awake, but nothing should
  depend on it;
- rendering the *upcoming* minute to beat the repaint boundary is moot;
- `setRenderAmbient()` remains useful for a different reason: whatever image is last delivered before
  dozing should already have its seconds hidden, since it may be on screen for minutes.

## 7u. Tap routing across stacked slots - the full-screen images must keep the lowest ids

Raised by the maintainer from the code-based CWF work: with two complications in the same place,
**painting** priority goes to the later one but **tap** priority goes to the earlier one. That is not
a CWF quirk, it is androidx's rule, and it matters as soon as the document stacks slots.

`ComplicationSlotsManager.getComplicationSlotAt` (see `Complication_Libraries.md`, "Tap routing -
reads cached bounds only") resolves a tap with
`findLowestIdMatchingComplicationOrNull { it.enabled && it.tapFilter.hitTest(...) }`. Two
consequences:

- **by lowest slot id**, regardless of where anything is painted;
- filtered only on `enabled` - **`alpha` is never consulted**, so a slot made invisible with
  `alpha="0"` or an AMBIENT `Variant` **stays tappable**.

*Inference, flagged as such:* this is androidx's own code and the WFF runtime is an androidx watch
face (its dump prints androidx `ComplicationSlotsManager` blocks), so the rule should hold - but
`Complication_Libraries.md` warns against assuming WFF matches androidx for **rendering**, and this
has not been tested on the WFF runtime. **Verify on device** once ambient slots exist.

**Rule for this document, to be kept deliberately rather than by luck:** the two full-screen image
slots keep the **lowest** ids (0 = lower, 1 = upper). They then capture every tap anywhere on the
face, and any ambient-only text slot added later can never receive one - which is what we want, since
those are invisible while the watch is awake. Both image slots carry the same `MENU` action, so it
does not matter which of the two wins.

Numbering an ambient slot 0 or 1 would send taps to invisible text. Anyone adding slots must keep the
images lowest.

## 7q. Two open UX gaps on the pushed face (observed 2026-09-01, not yet addressed)

Both raised by the maintainer while testing POC 3. Recorded with what is already known about the
cause; neither is investigated in depth yet.

### The long-press preview shows the default face, not the loaded zip

Long-pressing the face to switch watch face or reach preferences shows a picture that looks nothing
like the zip currently loaded. With the code-based CWF this could be kept in step - `WatchFace` has
`requestPreviewImageUpdate()`, and the system's cached preview is discussed in
`Complication_Libraries.md`.

**Actual cause - ours, and fixable.** An earlier version of this entry blamed the static
`<Preview value="@drawable/preview" />` in `watch_face_info.xml` and tied the problem to the token
blocker. **That was wrong**, as the maintainer pointed out: the editor does not merely show that
resource, it renders the face using each complication's **preview data**. Ours returns
`R.drawable.watchface_custom`, the *built-in default* Custom watch face picture - which is exactly
the unrelated image being seen.

**Fix - use the zip's own image.** Every valid zip is guaranteed to contain one:
`ZipWatchfaceFormat.loadCustomWatchface` rejects a zip whose `resData` lacks
`ResFileMap.CUSTOM_WATCHFACE`, because that is the picture the phone's watch face list shows. So
`getPreviewData()` can decode that instead of the built-in default, and the long-press preview then
looks like the face the user actually has.

This stays inside the constraints that made the preview static in the first place: `getPreviewData()`
runs on the **binder thread**, so it must not inflate views or build a second `CustomWatchface` -
decoding a stored PNG does neither. It reads `resData`, not the json, so it also does not touch the
rule that `CustomWatchface` is the sole reader of the json.

The static `@drawable/preview` in the APK remains whatever the gallery uses, and updating *that*
would still need a re-tokenised APK - but it is a separate, lesser problem.

### Long-press -> preferences does nothing

**Cause confirmed 2026-09-01: the Samsung editor crashes.** It is not showing an empty screen, it is
dying:

```
Process: com.samsung.wear.watchface.runtime
java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
  at java.util.ArrayList.get
  at com.samsung.wear.watchface.runtime.editor.C.q
  at com.samsung.wear.watchface.runtime.editor.C.onViewCreated
```

Our document declares **both** slots `isCustomizable="FALSE"` (§7b Q1, to stop a user breaking the
layout) and contains no `ListConfiguration` or `BooleanConfiguration`, so the editor's list of
editable items is empty - and it indexes element 0 of it without checking. `watch_face_info.xml`
still says `<Editable value="true" />`, which is a claim the document can no longer honour.

**So this is a defect in the Samsung editor, triggered by a legitimate document.** Two ways out:
declare something editable (which the ambient toggle of §7i would do anyway, so it may fix this as a
side effect), or set `<Editable value="false" />` so the editor is never opened. The second is
honest but loses the entry point entirely.

**Context that shapes the fix** (maintainer): AAPS watch face preferences live in two places today -
the shared ones in the AAPS application preference menu, used by the remaining code-based faces
(digital, circle), and dedicated ones in the long-press menu and the AAPS watch face menu. For the
Custom watch face the dedicated menu is **dynamic**: it only lists what the loaded zip actually uses,
so a zip with no complication slots hides the complication entries entirely.

**Two directions, neither chosen:**

1. Put the dedicated preferences into the WFF face's own editor, via `ListConfiguration` /
   `BooleanConfiguration`. Limited - those are static lists chosen in the system editor, and cannot be
   dynamic per zip (§7i), so the "only show what this zip uses" behaviour would be lost.
2. Keep them in AAPS and make the CustomWatchface preference entry reachable. It is currently
   **disabled when the Samsung SysUI library is absent**, which is exactly the case on the watches
   this topic targets. That gating would have to change.

Direction 2 preserves the dynamic behaviour and does not fight the format; direction 1 is more
native but strictly less capable. Related: §1 already records that on GW7+ the whole CWF
configuration path is dead because it assumes the system treats `CustomWatchface` as selectable.

## 7s. Branch strategy, and who is reviewing

`wear/cwf_wff` is built on **`wear/CWF_Complication`**, not on `dev`, deliberately (maintainer,
2026-09-01):

- `CWF_Complication` is the maintainer's own latest CustomWatchface work and is expected to merge -
  so basing on it is not a risk.
- It keeps the WFF work continuously exercised **against the complication feature**, so a regression
  in one shows up while working on the other. That is how the shared-enum state corruption (§7d,
  fixed in the ownership marker) and the background rotation bug were both caught.
- It avoids merge conflicts between two parallel branches touching the same files.

**Shared for feedback 2026-09-01** with two other developers: the author of the WFF AAPS V4 face, and
an owner of a **GW7 Ultra on current firmware**, which can no longer run the code-based CustomWatchface.

The GW7 test is the one that matters most: **everything so far has been measured on a GW4**, the one
watch that cannot exhibit the problem this topic exists to solve. Constructing a `CustomWatchface` on
firmware that refuses to *bind* one is reasoned-safe (§7d - construction is inert, and
`BgGraphComplication` already renders on GW7/8/9) but has never been tested.

**Reusing the AAPS V4 slot is agreed, not merely assumed.** Its author (Olorin) built that face to
test the graph complication, and agreed with the maintainer *before this development started* that it
could be reused for the Custom watch face if this turned out to work. So replacing the document is
the intended outcome, not a decision needing defence.

The alternative remains costed in §7o should it ever be wanted - a user-facing choice of which face
holds the single slot - but nothing is asking for it.

## 7v. Colouring the ambient glucose value - not possible today, and the route that would work

The ambient readouts are grey. Colouring the glucose by level, as the code-based face does, is not
available and the reason is worth recording so it is not re-attempted the wrong way.

**A provider cannot colour text.** `ShortTextComplicationData` carries `text`, `title`,
`monochromaticImage`, `smallImage` and a content description - **no colour field**
(watchface-complications-data 1.3.0, `Data.kt`). So "let the complication colour it" is not
expressible for SHORT_TEXT, even with a complication rewrite.

**A document cannot compute it either.** `Font`'s `color` is `colorAttributeType`, whose pattern is
`\[[A-Z0-9]+([._]\w+)*\]|#hex` (`common/simpleTypes/colorType.xsd:45-55`) - a hex value **or a
single data-source reference**. No expressions, no conditionals. And a SHORT_TEXT complication
exposes no numeric value to compare against anyway.

**The route that would work, and it keeps the colour with AAPS.** `RangedValueComplicationData` has
`colorRamp: ColorRamp?` (`Data.kt:1213`), where `ColorRamp(colors: IntArray, interpolated: Boolean)`
takes up to 7 colours, and with **`interpolated = false`** they render as *"equal sized regions of
solid colour"* (1098-1106) - discrete bands rather than a gradient. WFF exposes
**`[COMPLICATION.RANGED_VALUE_COLOR_INTERPOLATE]`** as a colour data source, which matches
`colorAttributeType`, so it can be used directly as `<Font color="...">`.

The bands are **equally sized**, so thresholds are set by choosing the range rather than stated
directly. For 70 and 180 with three colours: `min = -40`, `max = 290`, giving `-40..70`, `70..180`,
`180..290`. Contrived, but it puts the boundaries exactly where AAPS wants them, and **AAPS supplies
the colours**.

**What it costs:** a glucose provider serving `RANGED_VALUE` rather than `SHORT_TEXT`
(`SgvComplication` is SHORT_TEXT only today), and the document rendering the value from
`[COMPLICATION.RANGED_VALUE]` instead of `[COMPLICATION.TEXT]`. Also **unverified**: whether the WFF
runtime honours `interpolated = false` as bands, and whether that data source is usable for text
colour at all rather than only for a progress ring.

Not scheduled. Grey is legible and honest in the meantime.

## 7w. The wear process is being killed and restarted every few seconds - URGENT, cause unknown

**Observed 2026-09-01 18:40-18:45, watch idle and on charge.** The AAPS wear process is killed and
relaunched continuously:

```
Process info.nightscout.androidaps (pid 7585) has died: psvc PER (82,307)
  Scheduling restart of ... WatchFaceControlService in 19800000ms for connection
  Scheduling restart of ... DataLayerListenerServiceWear in 1000ms for start-requested
Process info.nightscout.androidaps (pid 7657) has died: psvc PER (73,307)
Process info.nightscout.androidaps (pid 7708) has died: psvc PER (69,309)
Process info.nightscout.androidaps (pid 7776) has died: psvc PER (65,311)
```

**23 process launches** were counted in the log window, with a new pid each time, roughly every 5-8
seconds. `DataLayerListenerServiceWear` is restarted after 1 s each time, which relaunches the
process, which dies again - a loop. `WatchFaceControlService`'s restart backoff climbs
(19800000 -> 21600000 -> 23400000 -> 25200000 ms), so the system is treating these as repeated
crashes.

**Why this matters more than anything else currently open.** It quietly invalidates several things:

- **The warm instance is fiction.** It is discarded on every kill, so renders keep paying full
  construction - which is very likely the real source of the unexplained 1-9 s "stalls" recorded in
  §7l and §7p. Those were probably cold starts, not mysterious scheduling.
- **`showsSeconds()` reads false** after each restart until something has been drawn, so the clock
  rate keeps falling back to the slow path.
- The repeated `ambient=true` log lines with no `false` between them are explained the same way: a
  new updater each time, each logging its first observation.
- Battery: relaunching a process every few seconds is expensive.

**CAUSE FOUND AND FIXED (2026-09-01 19:08): we were shipping raw bitmaps across Binder.**

`Icon.createWithBitmap` carries uncompressed pixels: a 450x450 ARGB image is **791 kB**, and two of
them travelled to the system on every refresh - up to once a second. Roughly 1.6 MB/s of Binder
traffic, against a transaction limit of about 1 MB. Switching to `Icon.createWithData` with a PNG:

```
UPPER image   8-9 kB compressed, was 791 kB raw     (~88x smaller)
LOWER image     54 kB compressed, was 791 kB raw    (~15x smaller)
```

**Result: the kill loop stopped dead.** Before the change, 23 process launches in one window and the
last kill at 18:47:25. After installing at 19:08:40: **zero kills**, one process start, still running
at 19:10.

**What this retroactively explains** - three things previously filed as unexplained:

- the **1-9 s render "stalls"** of §7l and §7p were cold starts after a kill, not scheduling oddities;
- the **warm instance** looked ineffective because it was being destroyed every few seconds;
- the erratic cadence, the repeated `ambient=true` logs, and `secondsShown` falling back to false all
  followed from the same restarts.

It also means the measurements in §7l and §7p understate what the design can do, since most of them
were taken while this was happening. Worth re-taking.

**Superseded hypothesis, kept for the record:** memory pressure - the warm `CustomWatchface` holds a
whole view hierarchy plus the zip's decoded images, and renders allocate a 450x450 bitmap up to once
a second. `dumpsys meminfo` could not be read before the watch went offline.

- the process was killed while **visible and holding a foreground service**, which ordinary trimming
  does not do;
- **only our app** was killed - 60 kills in one window, no other process - so it was never
  device-wide pressure;
- `dumpsys meminfo` showed 62 MB PSS against 244 MB available, which was the clue that memory was not
  the problem and something else about our behaviour was.

## 8. Decisions log

*(append-only: date + decision + why)*

- **2026-08-30** — Topic opened.
- **2026-08-30** — A1–A5 answered by the user (§5). Two design-fixing facts: **every existing zip
  must work and zips are unknown at build time** (so the WFF document must be generated at
  runtime), and **the code-based CWF must keep working for GW4–6** (so this is an addition, not a
  replacement). S1/S2 promoted to gating spikes.
- **2026-08-30** — Correction A2b: CWF resources are **not** a closed name set. Dynamic images
  (`dynData` `image1..N`, per-view `background`) and custom fonts are referenced by arbitrary file
  names taken from the zip, unbounded in name and count. A pre-declared resource pool is still
  workable (we control the name mapping) but becomes a **capacity** question, not a naming one.
- **2026-08-30** — Added §7b: the two roles of complications (bridge vs user slots), plus the
  locking (Q1) and hiding (Q2) questions. Q1 has a plausible lever (`isCustomizable="FALSE"`);
  Q2 currently leans negative (privileged permission + observed platform deviation). Spikes
  S10–S12 opened.
- **2026-08-30** — **Option A v1 scope fixed: no complication slots.** Every zip currently shared
  between users has none (the feature is not merged into `dev` yet), so a v1 without slots covers
  100 % of the zips in circulation. Complications on WFF stay an open item (§7f).
- **2026-08-30** — Architecture decision: **A-2** (§7d) — render through an inert `CustomWatchface`
  object in the provider process; no `Service` lifecycle touched. Debt recorded in §7d.
- **2026-08-31** — **POC 1 passed on device** (GW4, Wear OS 6.0 / One UI 8.0): a render-only
  `CustomWatchface` draws the user's own zip correctly to a bitmap. A-2 validated on hardware, so
  the architecture decision above is now backed by a device test and not only by source reading.
  Open follow-up: **refreshing is the host's job** — for Option A that is the complication update
  cadence (S3), still unmeasured.
- **2026-08-31** — **Push measured: 15-115 ms delivery, no throttling.** The 1m35s-6m19s figure
  applies to the periodic path only. The limit is our render cost (246 ms typical, 6-9 s outliers),
  not the platform. See §7l.
- **2026-08-31** — **Clock design decided (§7l):** an **analog** second hand is one image from the zip
  rotated by WFF via `Transform` on `[SECOND]`, costing **no** recurring updates; **digital** time or
  seconds stay **our** mechanism, a small image rendered by us and pushed. The zip keeps owning the
  design in both cases - WFF only rotates, it never draws its own clock.
  Consequence: a per-second small render must not build a fresh `CustomWatchface`, so the warm-instance
  optimisation moves from "maybe later" to a requirement of the digital path.

---

*Draft — pending review. Claims are labelled where they matter: §2 is read from this repository,
§7a/§7e and the `Complication_Libraries.md` entries are read from library sources and the WFF
schema, and POC 1 (Phase 2) is verified on a device. Anything else is analysis and should be
treated as such.*

## §7x - Ambient style and charging view: what the user can choose, and where

Raised by the user 2026-09-01: should there be a preference for "dimmed CWF" versus "simplified
watch face" in ambient, and a charging view? Recorded here because §7i/§7j analysed the ambient
options but no plan item ever came out of it.

**Charging is the easy half.** The schema exposes `BATTERY_CHARGING_STATUS` (see
`Complication_Libraries.md`, "WFF knows the battery and the charger"), so a charging layout is a
`Condition` inside the document. That matters more than convenience: on a charger the watch dozes and
our process is frozen, so a charging view that depended on us pushing images would freeze with it. A
condition the runtime evaluates does not.

**Ambient style is the hard half, and the difficulty is not technical.** Both looks are buildable.
The question is where the switch lives:

| Where | How | What it costs |
|---|---|---|
| Watch face editor | `BooleanConfiguration` / `ListConfiguration` in the document | A second place to configure, unrelated to AAPS settings. AAPS cannot read or set it (§7b/§7f). Validates against the schema; **runtime unverified**. |
| AAPS preferences | We already own the ambient rendering, so AAPS decides what it draws | Consistent with `key_simplify_ui` (`off`/`ambient`/`charging`/`ambient_charging`, read by `SimpleUi.isEnabled`), which users already know - but only works for what **we** draw, not for the document's own layers |

The constraint that shapes it: **the app is frozen in ambient**, so an ambient view made of our images
goes stale, which is why the current design hides the image and shows a native clock plus text slots.
A "dimmed CWF" ambient therefore means showing a *stale picture*, dimmed - acceptable for a design
without seconds, misleading with one.

**Not started. Open questions before any code:**

- [ ] **S13** Does `[CONFIGURATION.*]` actually work at runtime on a pushed face, or only validate?
- [ ] **S14** Simple mode parity: `key_simplify_ui` has four values and the WFF face honours none of
      them. Charging in particular is not handled at all today.
- [ ] **S15** If the ambient style setting lives in AAPS, what exactly does "dimmed CWF" draw when
      the design has seconds, given the picture cannot update while dozing?

