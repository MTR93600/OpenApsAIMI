# Replay fixtures

Days of real loop decisions, projected from AIMI support packages, used by
`app.aaps.plugins.aps.openAPSAIMI.replay`.

Study placement: `plugins/aps/src/commonTest/` (pure Kotlin, `kotlin.test`). This is the
APS barrier harness — not `quality/ReplayQualityExport`.

## What is bundled here

### Day fixtures

| File | Day | Size | Why it is kept |
|---|---|---|---|
| `day_in_range.jsonl` | 2026-08-03 | ~150 KB | 95.4 % time in range, 0 % below 70. **The non-regression reference**: no change may alter this day's decision stream unless the ADR states the expected delta. |
| `day_rebound_cycles.jsonl` | 2026-08-04 | ~152 KB | Four chained post-hypo correction cycles, 56.76 U of SMB, 10.96 U of it delivered on ticks the engine classified `REBOUND_GUARD`. The day ADR 0006 targets. |
| `day_hyper.jsonl` | 2026-07-22 | ~173 KB | 21 % above 180 with 36 U delivered during the hyper. Shows the loop dosing hard rather than being held back, which is what ruled out a "missing hyper guard" hypothesis. |

P0.12 bundled only the barrier ticks below; these three were left on `origin/dev_OAPSAIMI` at that
point ("out of P0.12"). They are now restored, byte-identical to commit `556580a886`, read from the
same `DayInRangeJsonl` / `DayReboundCyclesJsonl` / `DayHyperJsonl` Kotlin-string pattern as
`BarrierTicksJsonl`, so `ReplayCorpus.bundled` and `ReplaySummary` are actually exercisable on every
KMP target, not just documented.

### Barrier ticks

| File | Size | Why it is kept |
|---|---|---|
| `barrier_ticks.jsonl` | ~42 KB | 72 real ticks where `ControlBarrierShield` actually ran, used by `BarrierReplay`. **The input of the replay the coefficient floor needs** — see the note on `InsulinActionModel.controlCoefficient`. |

A day fixture above is 3.5-4x the size of this one, because it holds every tick of a real day rather
than 72 round-robin-sampled ones — worth checking before bundling a fifth day.

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
