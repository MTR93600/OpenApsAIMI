# Replay fixtures

Days of real loop decisions, projected from AIMI support packages, used by
`app.aaps.plugins.aps.openAPSAIMI.replay`.

Study placement: `plugins/aps/src/commonTest/` (pure Kotlin, `kotlin.test`). This is the
APS barrier harness — not `quality/ReplayQualityExport`.

## What is bundled here

Day fixtures (`day_in_range.jsonl`, `day_rebound_cycles.jsonl`, `day_hyper.jsonl`) live on
`origin/dev_OAPSAIMI` and are **out of P0.12**. They are not copied into the study tree.

### Barrier ticks

| File | Why it is kept |
|---|---|
| `barrier_ticks.jsonl` | 72 real ticks where `ControlBarrierShield` actually ran, used by `BarrierReplay`. **The input of the replay the coefficient floor needs** — see the note on `InsulinActionModel.controlCoefficient`. |

This one is **not a day** and is not listed in `ReplayCorpus.bundled`. The barrier only runs on about
a third of ticks, so a day of it would be two thirds empty, and a day summary of it would be
meaningless.

It is also cut differently from the day fixtures, because it is meant to be safe to keep in a public
repository whatever it is regenerated from:

- **no clock.** `t` is an offset in milliseconds from the first tick of the file and `tmin` the same
  offset in minutes. There is no date and no time of day.
- **no identifiers.** No event id, no trigger name, no decision text, no owner. Every value in the
  file is a number or a boolean.
- **only what the barrier reads.** Glucose, IOB, carb appearance, the barrier's own terms, the
  controller's raw request, the profile basal, the IOB ceiling and the two sensitivities.

The 72 ticks are picked round-robin over the strata that matter for the barrier — the gamma branch,
whether the dose was fully suspended, and whether the barrier had to intervene at all — so the small
file still holds suspended ticks, passing ticks, accelerated ticks and relaxed ticks.

`ReplayCorpus.load` reads the same bytes from `BarrierTicksJsonl` so the fixture is available on
every KMP test target (no `ClassLoader`).

## Format

One flat JSON object per line, sorted by timestamp, short keys, only the fields the harness reads.
A full package is 8–13 MB; a fixture is around 150 KB.

Every field is optional. Fixtures captured before a field existed simply omit it — that is
deliberate, so an old day stays loadable and comparable. `ReplayTick` models this with nullable
properties throughout. Parsing uses kotlinx `JsonObject`, not `org.json`.

## Regenerating

```
python3 scripts/aimi_replay_fixture.py \
    ~/Downloads/AIMI_Support_Package_<id>/AIMI_Decisions_Last24h.jsonl \
    plugins/aps/src/commonTest/resources/replay/<name>.jsonl
```

And for the barrier fixture:

```
python3 scripts/aimi_replay_fixture.py --barrier --max 72 \
    ~/Downloads/AIMI_Support_Package_<id>/AIMI_Decisions_Last24h.jsonl \
    plugins/aps/src/commonTest/resources/replay/barrier_ticks.jsonl
```

If a regenerated fixture changes the figures asserted in `BarrierReplayTest`, that is a signal to
investigate before updating the expected values, not a reason to update them.

## Private corpus

Regression needs stable data; **calibration needs data that can contradict you**. A bundled or
synthetic set only contains patterns someone already believed in.

Keep additional packages outside the repository and point the harness at them:

```
export AIMI_REPLAY_CORPUS=~/aimi-corpus
```

`ReplayCorpus.loadLocal()` returns an empty map on the study `commonTest` source set (no host
filesystem API). Callers must treat that as *skip*, never as *nothing to report*.

**Do not add another person's package here.** A 24-hour glucose curve is recognisable by the person
it belongs to, this repository is public, and git keeps history after deletion. Third-party packages
belong in the private corpus only.
