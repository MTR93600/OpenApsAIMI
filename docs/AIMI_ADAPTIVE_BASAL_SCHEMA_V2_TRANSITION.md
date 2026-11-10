# Adaptive basal: learned models are reset once (schema v2)

**Status:** draft, needs review before it is published or relied upon.
**This is not medical advice.** Talk to your care team about anything that keeps happening with your levels.

---

## For users: what changes, in plain words

AIMI can learn a small "adaptive basal" factor from your own data. That factor multiplies the basal
rate the algorithm asks for. A value of 1.0 means "no change".

We found a fault. On affected setups the learned factor was stuck at **0.70** — a permanent 30 % cut
to the basal channel, on every single loop cycle, around the clock, including during meal rises. It
was not a decision the app had made from your data. The learning part had never worked, and the value
you saw was simply the lowest value the app was allowed to use. It was confirmed on two independent
devices, and on one of them it had been stuck for 40 days.

**What happens the first time you run this update:**

- Every learned model on the device is dropped: adaptive basal, T3C, and the SMB refinement model.
- Each part falls back to its built-in behaviour, which does not depend on a learned model.
- The app starts learning again from scratch, from your own data.

**What you will notice:**

- The adaptive basal factor goes back to a neutral value instead of 0.70. If your setup was affected,
  **the basal channel is no longer being cut by 30 %.** This is the point of the fix, but it is still a
  real change to how much insulin the loop asks for.
- T3C aggressiveness returns to neutral until a new model is learned.
- The Advisor no longer shows a percentage for the personal on-device model, and that model no longer
  affects any Advisor suggestion. The number it used to show looked like a risk percentage but was not
  one: it could never go below 50 %, so it did not mean what it appeared to mean. The Advisor's other
  inputs — your measured time low and time high, and the built-in risk model — are unchanged.

**Please treat the first days after this update as a change worth watching**, in the same way you
would watch any change to your settings. If your setup was affected, the loop is no longer holding
back basal the way it was, and that is a change in the direction of more insulin on the basal channel.
Review it with your care team. Nothing in this document is a dosing instruction, and no dosing
recommendation can be given here.

**How long until learning comes back?** It depends on how much usable data your device has collected.
The app only learns from time windows it can actually score, and it now refuses windows that were
disturbed by a bolus or by carbs, because those say nothing about basal. That means fewer, better
windows. Until there are enough of them, the built-in behaviour stays in charge. This is the safe
state, not a failure.

---

## For maintainers: why the reset is unavoidable

The weight files on disk are not recoverable. They do not contain a model that needs retraining — they
contain a model that could never have worked:

- The shared network applied its weight decay **once per training sample** while Adam normalises the
  step to about the learning rate. Weights therefore settled at `learningRate / weightDecay` = 0.05.
- With layer norm on, the hidden vector is forced to unit scale, so the output was bounded at roughly
  `sqrt(hiddenSize) * ||W2|| + bias` ≈ 0.29, against a label range of 0.7 .. 1.5. **The reachable
  output range did not intersect the label range.**
- `biasHidden` and `biasOutput` were never trained. Layer norm makes the hidden vector zero-mean, so
  the expected output equalled `biasOutput`, frozen at its init value of 0.01.
- On a real device artefact, 7 of 16 input rows had decayed into **denormal** doubles (2.42e-322):
  those features had zero gradient, so only the decay applied, about 73 400 times. Roughly one training
  cycle is enough to do that.
- Measured on that artefact: output constant at **0.19918**, spread over bg 40..400 of **3.4e-08**,
  and a maximum response to any single feature over its full range of **2.7e-06**.

The runtime clamp then turned 0.19918 into exactly 0.70, which is why the symptom read like a
confident decision instead of a dead model.

### What the schema bump does

`AimiNeuralNetwork.SCHEMA_VERSION = 2` is written on save and checked on load. A file without it, or
with a different value, is refused — including the `.bak` sibling, which would otherwise resurrect the
same dead weights. This is deliberate and applies to all four heads that share the class (basal, T3C,
SMB refinement, oref advisor).

### Retraining cadence after the reset

| Head | Minimum rows | Interval |
|---|---|---|
| Basal | 100 scorable rows | hourly |
| T3C | 50 scorable rows | hourly |
| SMB refinement | 200 new rows | 6 h |

"Scorable" is stricter than before: a window is dropped unless the realised 30-minute change is at
least 3 mg/dL, unless the correction direction is consistent, and unless no bolus or carbs entered the
window. A correction that behaves itself converges on target, so a single episode only yields a
handful of scorable windows — real data supplies them because it contains many episodes.

### How to confirm it is actually working on a device

Read `adjustments.adaptive_basal` in `AIMI_Decisions.jsonl`:

- `n_source` should read `heuristic` right after the update, then `neural` once a model publishes.
- **`n_raw` is the field that matters.** It is the learned value *before* the runtime clamp. It must
  vary with bg. A constant `n_raw` is the exact failure that survived 40 days, and it is invisible in
  every other field.
- `n_mult` should not sit on the runtime floor (0.80) or on the ceiling for essentially every tick.

A model that publishes must move by at least 0.05 across the bg anchors 70 / 140 / 250 mg/dL, and must
beat the best constant predictor on held-out rows. The publish gate and the runtime health probe read
the **same** anchor list on purpose: if they sweep different windows, the app can publish a model that
its own loader then refuses, and nothing in the logs links the two events.

### Known limits of this change

- The heuristic channel (`hMult`) still floors at 0.70, and `BasalLearner.CLAMP_MIN` is still 0.70.
  Only the learned channel was raised to 0.80. Whether the heuristic floor should move too is a therapy
  decision that needs field evidence, not a code cleanup.
- The oref advisor head is contained, not fixed. Its objective and readback still disagree, so its
  score is not a probability. Re-deriving its thresholds is separate work.
- Runtime behaviour after this change is **unverified on a device**. Everything above is measured in
  test and against one real weight file. Build success is not feature completion.
