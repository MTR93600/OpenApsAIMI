<div align="center">

# AIMI

### An adaptive, physiology‑aware evolution of AndroidAPS

*Automated insulin delivery that models the **person**, not just the glucose curve.*

**Ten years of daily development, reflection, research and innovation** — a decade of lived, day‑after‑day
iteration distilled into an engine.

> 🌿 **The most advanced version lives on the [`dev_OAPSAIMI`](../../tree/dev_OAPSAIMI) branch.**
> That is where active development happens and where every capability below is implemented.

</div>

---

## What is AIMI?

AIMI is a research‑grade fork of **[AndroidAPS](https://github.com/nightscout/AndroidAPS)**, the open‑source
artificial‑pancreas system. It keeps AndroidAPS's proven safety foundation and pump/CGM integrations, and rebuilds
the **decision layer** around a single idea:

> **Insulin needs are driven by physiology — meals, activity, stress, sleep, hormones, insulin kinetics — so the
> loop should reason about that physiology directly, learn each person's response, and adapt in real time.**

Where classic loops react to glucose and its trend, AIMI maintains a live **physiological model** of the user and
lets that model shape prediction, basal, and micro‑bolus decisions — always behind the same hard safety limits.

---

## Why it's different

| Classic AID loop | AIMI |
|---|---|
| Reacts to BG + delta | Maintains a **physiological state model** (activity, meals, circadian, stress, thermal) |
| Fixed insulin curve (static DIA/peak) | **Learns your DIA/peak** and reshapes the glucose predictions around it |
| One global behavior | **Per‑context personalization** — child, woman (cycle‑aware), adult, brittle/T3C |
| Rule cascade | Rules **+ on‑device neural networks** that learn your glycemic response |
| Telemetry as an afterthought | **Structured study‑data pipeline** so the system can be measured and improved |

---

## Core innovations

### 🧠 A physiological decision engine (Harmonia)
AIMI builds a **physiological tree** every loop tick — a structured belief about the body's current state
(activity/effort, meal absorption, circadian sensitivity, transient resistance, post‑hypo recovery). A decision
engine, **Harmonia**, reads that tree and can drive the pump (basal + SMB), gated by the unchanged safety layer.
Physiology isn't advisory — it has real, bounded authority over dosing.

### 📈 Adaptive PK/PD — predictions shaped by *your* insulin
AIMI continuously estimates your **effective DIA and insulin peak** and now feeds those *learned* kinetics into the
glucose‑prediction curves themselves (eventual / minPred / the PK/PD graph). The forecast reflects how **your**
insulin actually acts, not a textbook curve — the missing link between "adaptive kinetics" and the decisions that
use them.

### 🤖 On‑device machine learning
Dedicated, privacy‑preserving neural networks train **on the phone** from your own history:
- an **SMB refinement** model, and
- a **basal / T3C** model,

both fed a **shared physiological feature set** (latent physio state + patient‑mode + causal context) so the models
see the same physiology the decision tree does — never a context‑blind regressor. Training runs in the background,
decoupled from usage, so a fresh model is always ready.

### 🚗 Autodrive V3 (MPC + safety filter)
A unified, model‑predictive autonomous engine that proposes micro‑boluses on confirmed rises through a
control‑barrier safety filter — a single, well‑bounded "hands‑off" product (the legacy engines were merged away).

### 🏃 Effort & activity intelligence
A single **effort‑belief** system fuses multi‑window step cadence, heart rate, HRV and stress to recognize real
activity (including the subtle "repeated small steps" pattern), remembers recent effort, and applies graded,
**reduction‑only** protection — and it refuses to mistake a post‑exercise adrenaline rise for a meal.

### 🩸 Adaptive CGM smoothing (Unscented Kalman Filter)
A UKF + RTS smoother tuned for real‑world, irregular feeds (xDrip / Notification Listener), with **patient‑relative
compression‑artifact detection** (is a drop physiologically explainable by *this* person's insulin?) — minimal lag
on fast rises, hypo‑safe on lows.

### 👶🚺🧑 Personalization for a normal life
AIMI already models the person's context, which most AID systems don't:
- **Child** — small‑dose robustness, dawn/growth, unpredictable activity, tolerance to imprecise meal announcement.
- **Woman** — menstrual **cycle‑phase** sensitivity modulation.
- **Adult** — stress, variable schedule, exercise.
- **T3C** — pancreatectomy / CFRD adaptations (brittle mode, exacerbation support).

### 📸 Photo‑based carb estimation (opt‑in, safety‑gated)
For anyone who wants it, AIMI can estimate a meal's carbs **from a photo** via an on‑request AI vision model. It's
built to be **cautious, not clever**: the model returns separate **food** and **portion confidence** levels and a
hidden‑carb risk, the response is **sanitized**, and a low‑confidence or unclear reading is **downweighted or
rejected** rather than trusted — the system would rather ask you than dose on a blurry guess. Fully optional, and
always a *suggestion* that you confirm.

### 🔬 Hormonitor — measurable by design
A structured on‑device study‑data pipeline (decisions, outcomes, blackbox, physiology) **with an in‑app viewer**
(indexed, English/French), so the loop's behavior can actually be read, audited, and improved — closing the
feedback loop that most DIY systems leave open.

---

## The cockpit — you stay in command

AIMI is powerful, so it's built to be **legible and steerable**, not a black box:

- **🎛️ Control Center** — a single cockpit to set the system's **autonomy** (from observation‑only up to controlled
  authority) and its **behavior families** (stability, meal capture, protection, physiology) with human‑readable
  levels, instead of hunting through dozens of raw switches.
- **🧭 Advisor** — a suite of assistants (profile, PK/PD, carbs, meal, straight‑line insulin) that **surface
  reasoning and suggestions** and let you apply them deliberately — guidance, not silent overrides.
- **🕵️ Auditor** — an independent "second brain" that **reviews the loop's own decisions** against a stable context,
  caches verdicts, and can gate or flag a decision when its confidence is low — a check on the checker.

---

## Architecture at a glance

```
        CGM  ─▶  Adaptive UKF smoothing  ─▶  bucketed BG
                                               │
   Physiology  ─▶  Physiological tree  ─▶  Harmonia decision engine ─┐
   (activity, meals,          ▲                                       ├─▶  Safety layer ─▶  Pump
    circadian, stress)        │                                       │    (unchanged AAPS
                    Adaptive PK/PD ─▶ prediction curves ─▶  Rule cascade + on‑device NNs  guards)
                                                               (SMB · basal · Autodrive V3)
                                               │
                                    Hormonitor telemetry ─▶ in‑app viewer / study data
```

Everything sits **on top of** AndroidAPS's constraint/safety machinery — AIMI changes how the loop *reasons*, not
the guarantees that keep it safe.

---

## Project status

AIMI is **advanced, actively developed, and experimental**. It is built for people who want a physiology‑first,
learning loop and are comfortable with a DIY, research‑grade system. Development and the newest capabilities are on
the **[`dev_OAPSAIMI`](../../tree/dev_OAPSAIMI)** branch; expect rapid iteration there.

Guiding principles: **stability and calibration over aggression**, physiology‑grounded decisions, and honest,
measurable behavior — because for someone living with diabetes, *trust* is what makes a life feel normal.

---

## Credits & safety

AIMI is a fork of **AndroidAPS** and stands on the shoulders of the OpenAPS / Nightscout / AndroidAPS communities —
all credit for the safety foundation goes to them.

> ⚠️ **This is experimental, do‑it‑yourself software for automated insulin delivery. It is not a medical device and
> not medical advice. You are responsible for your own settings and safety. Use at your own risk, and never dose
> insulin based on this system without understanding it.**
</content>
