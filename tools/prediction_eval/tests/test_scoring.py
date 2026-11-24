"""Tests for scoring.py and report.py. Synthetic data only."""

import math
import os
import tempfile
import unittest
from datetime import datetime

from zoneinfo import ZoneInfo

from tools.prediction_eval.model import STEP_MS, BgPoint, Dataset, Forecast, Tick
from tools.prediction_eval.report import ROWS_NAME, write_report
from tools.prediction_eval.scoring import (
    Row,
    actual_at,
    common_rows,
    context_for,
    conformal,
    evaluate,
    hypo_detection,
    nearest_rank_quantile,
    split_time,
    summarise,
    time_stratum,
)

MIN = 60 * 1000
SEC = 1000
T0 = 1_700_000_000_000
HOUR = 60 * MIN


def flat_bg(start_ms, end_ms, mgdl=120.0):
    return [BgPoint(t, mgdl) for t in range(start_ms, end_ms + 1, STEP_MS)]


def tick(time_ms, bg=120.0, iob=None):
    return Tick(time_ms=time_ms, bg=bg, series={}, iob_u=iob)


def offset_predictor(offsets):
    """Stub: centre = glucose at the tick + offsets[tick time] (default 0)."""

    def predict(ctx, horizon_min):
        return Forecast(centre=ctx.tick.bg + offsets.get(ctx.tick.time_ms, 0.0))

    return predict


def row(tick_ms, error, actual=120.0, low=None, high=None, predictor="p", horizon=30, strata=None):
    return Row(
        tick_ms=tick_ms,
        horizon_min=horizon,
        predictor=predictor,
        centre=actual + error,
        low=low,
        high=high,
        actual=actual,
        error=error,
        strata=strata or {},
    )


class ActualAtTest(unittest.TestCase):
    def test_tolerance_edges(self):
        bg = [BgPoint(T0, 100.0)]
        self.assertEqual(actual_at(bg, T0 - 150 * SEC), 100.0)
        self.assertEqual(actual_at(bg, T0 + 150 * SEC), 100.0)
        self.assertIsNone(actual_at(bg, T0 - 151 * SEC))
        self.assertIsNone(actual_at(bg, T0 + 151 * SEC))

    def test_nearest_wins_and_tie_takes_earlier(self):
        bg = [BgPoint(T0, 100.0), BgPoint(T0 + 2 * MIN, 110.0)]
        self.assertEqual(actual_at(bg, T0 + 30 * SEC), 100.0)
        self.assertEqual(actual_at(bg, T0 + 90 * SEC), 110.0)
        self.assertEqual(actual_at(bg, T0 + 1 * MIN), 100.0)

    def test_empty(self):
        self.assertIsNone(actual_at([], T0))


class ContextTest(unittest.TestCase):
    def test_never_after_tick(self):
        bg = flat_bg(T0 - 60 * MIN, T0 + 60 * MIN)
        ctx = context_for(tick(T0), bg)
        times = [p.time_ms for p in ctx.recent_bg]
        self.assertEqual(times[0], T0 - 30 * MIN)
        self.assertEqual(times[-1], T0)
        self.assertTrue(all(t <= T0 for t in times))


class StrataTest(unittest.TestCase):
    def _strata(self, bg, t, iob=None, tz="UTC", horizon=15):
        ds = Dataset(ticks=[tick(t, iob=iob)], bg=bg, tz_name=tz)
        rows = evaluate(ds, {"flat": offset_predictor({})}, horizons=(horizon,))
        self.assertEqual(len(rows), 1)
        return rows[0].strata

    def test_trend(self):
        falling = [BgPoint(T0 + i * STEP_MS, 150.0 - 4.0 * i) for i in range(-6, 6)]
        rising = [BgPoint(T0 + i * STEP_MS, 100.0 + 3.0 * i) for i in range(-6, 6)]
        stable = [BgPoint(T0 + i * STEP_MS, 100.0 + 1.0 * i) for i in range(-6, 6)]
        self.assertEqual(self._strata(falling, T0)["trend"], "falling")
        self.assertEqual(self._strata(rising, T0)["trend"], "rising")
        self.assertEqual(self._strata(stable, T0)["trend"], "stable")

    def test_trend_unknown_without_old_point(self):
        bg = [BgPoint(T0 + i * STEP_MS, 100.0) for i in range(-1, 6)]
        self.assertEqual(self._strata(bg, T0)["trend"], "unknown")

    def test_trend_ignores_future_values(self):
        # Flat before the tick, steep fall after: the trend must stay "stable".
        bg = [BgPoint(T0 + i * STEP_MS, 150.0 if i <= 0 else 150.0 - 20.0 * i) for i in range(-6, 6)]
        self.assertEqual(self._strata(bg, T0)["trend"], "stable")

    def test_iob(self):
        bg = flat_bg(T0 - 30 * MIN, T0 + 30 * MIN)
        self.assertEqual(self._strata(bg, T0, iob=3.0)["iob"], "high")
        self.assertEqual(self._strata(bg, T0, iob=2.9)["iob"], "low")
        self.assertEqual(self._strata(bg, T0, iob=None)["iob"], "unknown")

    def test_time_of_day_uses_local_zone(self):
        tz = "Europe/Paris"
        night = int(datetime(2026, 1, 10, 2, 0, tzinfo=ZoneInfo(tz)).timestamp() * 1000)
        day = int(datetime(2026, 1, 10, 6, 0, tzinfo=ZoneInfo(tz)).timestamp() * 1000)
        for t, expected in ((night, "night"), (day, "day")):
            bg = flat_bg(t - 30 * MIN, t + 30 * MIN)
            self.assertEqual(self._strata(bg, t, tz=tz)["time"], expected)
        # 06:30 UTC is day in UTC but 01:30 (night) in New York.
        late = int(datetime(2026, 1, 10, 6, 30, tzinfo=ZoneInfo("UTC")).timestamp() * 1000)
        bg = flat_bg(late - 30 * MIN, late + 30 * MIN)
        self.assertEqual(self._strata(bg, late, tz="UTC")["time"], "day")
        self.assertEqual(self._strata(bg, late, tz="America/New_York")["time"], "night")

    def test_evening_is_its_own_group(self):
        tz = "Europe/Paris"
        for hour, minute, expected in ((21, 59, "day"), (22, 30, "evening"), (23, 30, "evening"),
                                       (0, 30, "night"), (5, 30, "night"), (6, 0, "day")):
            t = int(datetime(2026, 1, 10, hour, minute, tzinfo=ZoneInfo(tz)).timestamp() * 1000)
            self.assertEqual(time_stratum(t, tz), expected, (hour, minute))

    def test_outcome(self):
        for value, expected in ((69.0, "hypo"), (70.0, "range"), (180.0, "range"), (181.0, "high")):
            bg = [BgPoint(T0, 120.0), BgPoint(T0 + 15 * MIN, value)]
            self.assertEqual(self._strata(bg, T0)["outcome"], expected)


class EvaluateTest(unittest.TestCase):
    def test_skips_missing_actual_and_none_forecast(self):
        bg = flat_bg(T0 - 30 * MIN, T0 + 30 * MIN)
        ds = Dataset(ticks=[tick(T0)], bg=bg)
        preds = {"flat": offset_predictor({}), "none": lambda ctx, h: None}
        rows = evaluate(ds, preds, horizons=(15, 30, 60))
        self.assertEqual(sorted((r.predictor, r.horizon_min) for r in rows), [("flat", 15), ("flat", 30)])
        self.assertTrue(all(r.error == 0.0 for r in rows))


class MetricsTest(unittest.TestCase):
    def test_hand_computed(self):
        rows = [
            row(T0, 10.0, low=100.0, high=140.0),
            row(T0 + STEP_MS, -20.0, low=125.0, high=135.0),
            row(T0 + 2 * STEP_MS, 50.0, low=110.0, high=200.0),
        ]
        summary = summarise(rows)
        m = [x for x in summary["metrics"] if x["stratum"] == "all"][0]
        self.assertEqual(m["n"], 3)
        self.assertAlmostEqual(m["bias"], 40.0 / 3)
        self.assertAlmostEqual(m["mae"], 80.0 / 3)
        self.assertAlmostEqual(m["rmse"], math.sqrt((100 + 400 + 2500) / 3))
        self.assertAlmostEqual(m["share_big_error"], 1.0 / 3)
        self.assertEqual(m["band_n"], 3)
        self.assertAlmostEqual(m["coverage"], 2.0 / 3)
        self.assertAlmostEqual(m["mean_width"], (40 + 10 + 90) / 3)

    def test_stratum_filter(self):
        rows = [
            row(T0, 10.0, strata={"trend": "falling"}),
            row(T0 + STEP_MS, -30.0, strata={"trend": "rising"}),
        ]
        metrics = summarise(rows)["metrics"]
        falling = [x for x in metrics if x["stratum"] == "trend=falling"][0]
        stable = [x for x in metrics if x["stratum"] == "trend=stable"][0]
        self.assertEqual(falling["n"], 1)
        self.assertAlmostEqual(falling["mae"], 10.0)
        self.assertEqual(stable["n"], 0)
        self.assertIsNone(stable["mae"])

    def test_hypo_detection(self):
        rows = [
            row(T0, 10.0, actual=60.0),  # centre 70: caught
            row(T0 + 1, 30.0, actual=60.0),  # centre 90: missed
            row(T0 + 2, -15.0, actual=95.0),  # centre 80: not below 80, no alarm
            row(T0 + 3, 50.0, actual=100.0, low=70.0, high=200.0),  # band low 70: false alarm
            row(T0 + 4, -10.0, actual=85.0),  # between 70 and 90: in neither group
        ]
        h = hypo_detection(rows)[0]
        self.assertEqual(h["n_hypo"], 2)
        self.assertAlmostEqual(h["sensitivity"], 0.5)
        self.assertEqual(h["n_no_hypo"], 2)
        self.assertAlmostEqual(h["false_alarm_rate"], 0.5)


class CommonRowsTest(unittest.TestCase):
    def test_predictor_that_skips_a_tick_drops_it_for_all(self):
        # "a" answers on all 3 ticks, "b" skips the middle one. The middle tick is dropped for both.
        rows = [row(T0 + i * STEP_MS, 1.0, predictor="a") for i in range(3)]
        rows += [row(T0, 1.0, predictor="b"), row(T0 + 2 * STEP_MS, 1.0, predictor="b")]
        # A big error of "a" on the skipped tick must not count against "a".
        rows[1] = row(T0 + STEP_MS, 90.0, predictor="a")
        kept, info = common_rows(rows)
        self.assertEqual(sorted({r.tick_ms for r in kept}), [T0, T0 + 2 * STEP_MS])
        self.assertEqual(info, [{"horizon_min": 30, "n_predictors": 2, "ticks_scored": 3,
                                 "ticks_common": 2, "ticks_dropped": 1}])
        summary = summarise(rows)
        mae = {m["predictor"]: m["mae"] for m in summary["metrics"] if m["stratum"] == "all"}
        self.assertEqual(mae, {"a": 1.0, "b": 1.0})
        self.assertEqual({e["n_no_hypo"] for e in summary["hypo"]}, {2})
        self.assertEqual(summary["common"], info)

    def test_each_horizon_on_its_own(self):
        # "b" has no answer at 60 min at all: at 60 min only "a" is compared, nothing is dropped.
        rows = [row(T0, 1.0, predictor="a", horizon=60), row(T0, 1.0, predictor="a"),
                row(T0, 1.0, predictor="b")]
        kept, info = common_rows(rows)
        self.assertEqual(len(kept), 3)
        self.assertEqual([e["ticks_dropped"] for e in info], [0, 0])


class ConformalTest(unittest.TestCase):
    def test_quantile(self):
        self.assertEqual(nearest_rank_quantile([5, 1, 4, 2, 3], 0.8), 4)
        self.assertEqual(nearest_rank_quantile([1, 2, 3, 4, 5, 6, 7], 0.8), 6)

    def test_split_by_time(self):
        rows = [row(T0 + i * STEP_MS, 1.0) for i in range(10)]
        self.assertEqual(split_time(rows), T0 + 7 * STEP_MS)

    def test_uses_only_train_errors(self):
        # Ticks one hour apart, so the 30-min embargo removes nothing.
        # Train ticks (first 7) have errors 1..7; test ticks (last 3) have error 100.
        rows = [row(T0 + i * HOUR, float(i + 1)) for i in range(7)]
        rows += [row(T0 + i * HOUR, 100.0) for i in range(7, 10)]
        e = conformal(rows)[0]
        self.assertEqual(e["n_train"], 7)
        self.assertEqual(e["n_test"], 3)
        self.assertEqual(e["q"], 6.0)
        self.assertEqual(e["width"], 12.0)
        self.assertEqual(e["coverage"], 0.0)
        self.assertAlmostEqual(e["test_mae"], 100.0)

    def test_test_coverage(self):
        rows = [row(T0 + i * HOUR, 10.0) for i in range(7)]
        rows += [row(T0 + 7 * HOUR, 5.0), row(T0 + 8 * HOUR, -10.0), row(T0 + 9 * HOUR, 11.0)]
        e = conformal(rows)[0]
        self.assertEqual(e["q"], 10.0)
        self.assertAlmostEqual(e["coverage"], 2.0 / 3)
        self.assertAlmostEqual(e["test_mae"], 26.0 / 3)

    def test_embargo_keeps_test_glucose_out_of_train(self):
        # Ticks every 5 min, split at the 8th tick (T0 + 35 min). At 120 min, a train row would
        # need its real glucose before the split, which no tick here has.
        rows = [row(T0 + i * STEP_MS, 1.0, horizon=120) for i in range(10)]
        rows += [row(T0 + i * STEP_MS, 1.0, horizon=15) for i in range(10)]
        split = split_time(rows)
        self.assertEqual(split, T0 + 7 * STEP_MS)
        by_h = {e["horizon_min"]: e for e in conformal(rows)}
        self.assertEqual(by_h[120]["n_train"], 0)
        self.assertIsNone(by_h[120]["q"])
        # At 15 min: ticks 0..3 have their real value (tick + 15) before T0 + 35. Tick 4 reaches
        # the split exactly, ticks 5 and 6 go past it.
        self.assertEqual(by_h[15]["n_train"], 4)
        self.assertEqual(by_h[15]["n_test"], 3)

    def test_tick_just_before_split_is_not_trained_on(self):
        # Ticks at 0..5 h, one at 6 h 50, then 7, 8, 9 h. The split is at 7 h. The 6 h 50 tick is a
        # train tick, but its real value at 60 min (7 h 50) is in the test period: it must be left out.
        times = [T0 + i * HOUR for i in range(6)] + [T0 + 7 * HOUR - 10 * MIN] + [T0 + i * HOUR for i in (7, 8, 9)]
        rows = [row(t, 500.0 if t == T0 + 7 * HOUR - 10 * MIN else 1.0, horizon=60) for t in times]
        self.assertEqual(split_time(rows), T0 + 7 * HOUR)
        e = conformal(rows)[0]
        self.assertEqual(e["n_train"], 6)
        self.assertEqual(e["n_test"], 3)
        self.assertEqual(e["q"], 1.0)

    def test_end_to_end_split_on_dataset(self):
        bg = flat_bg(T0 - 30 * MIN, T0 + 12 * HOUR)
        ticks = [tick(T0 + i * HOUR) for i in range(10)]
        offsets = {T0 + i * HOUR: (float(i + 1) if i < 7 else 100.0) for i in range(10)}
        ds = Dataset(ticks=ticks, bg=bg)
        rows = evaluate(ds, {"stub": offset_predictor(offsets)}, horizons=(30,))
        e = summarise(rows)["conformal"][0]
        self.assertEqual(e["q"], 6.0)
        self.assertEqual(e["coverage"], 0.0)


class ReportTest(unittest.TestCase):
    def test_writes_report_and_csv(self):
        bg = flat_bg(T0 - 30 * MIN, T0 + 200 * MIN)
        ticks = [tick(T0 + i * STEP_MS, iob=1.0) for i in range(10)]
        ds = Dataset(ticks=ticks, bg=bg, tz_name="Europe/Paris", notes=["synthetic test data"])

        def band(ctx, h):
            return Forecast(centre=ctx.tick.bg, low=ctx.tick.bg - 20, high=ctx.tick.bg + 20)

        rows = evaluate(ds, {"flat": offset_predictor({}), "band": band}, horizons=(15, 30))
        summary = summarise(rows)
        with tempfile.TemporaryDirectory() as out:
            path = write_report(ds, rows, summary, out)
            with open(path, encoding="utf-8") as f:
                text = f.read()
            self.assertIn("synthetic test data", text)
            self.assertIn("Number of ticks: 10", text)
            self.assertIn("How to read this", text)
            self.assertIn("glucose falling", text)
            self.assertIn("Calibrated band", text)
            self.assertIn("Low glucose detection", text)
            with open(os.path.join(out, ROWS_NAME), encoding="utf-8") as f:
                self.assertEqual(len(f.read().strip().splitlines()), 1 + len(rows))


if __name__ == "__main__":
    unittest.main()
