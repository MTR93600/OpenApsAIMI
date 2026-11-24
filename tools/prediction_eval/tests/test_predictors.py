"""Tests for predictors.py. Synthetic data only."""

import math
import unittest

from tools.prediction_eval.model import STEP_MS, BgPoint, Context, Tick
from tools.prediction_eval.predictors import BAND_PREDICTORS, PREDICTORS, measured_slope

T0 = 1_700_000_000_000
MIN = 60 * 1000


def make_series(start, step, length=25):
    return [start + step * i for i in range(length)]


def make_ctx(bg=150.0, slope_per_5=-6.0, series=None, recent=None):
    """A tick at T0. By default glucose fell by slope_per_5 each 5 minutes before T0."""
    if series is None:
        series = {"IOB": make_series(bg, -4.0), "UAM": make_series(bg, 2.0)}
    if recent is None:
        recent = [BgPoint(T0 - k * STEP_MS, bg - slope_per_5 * k) for k in range(6, -1, -1)]
    tick = Tick(time_ms=T0, bg=bg, series=series)
    return Context(tick=tick, recent_bg=recent)


class RegistryTest(unittest.TestCase):
    def test_all_names_present(self):
        expected = {
            "flat", "insulin_only", "scenario_best", "mean_iob_uam",
            "damped_trend_r0.7", "damped_trend_r0.8", "damped_trend_r0.85", "damped_trend_r0.9",
            "uam_fades_to_iob_60", "uam_fades_to_iob_90", "decaying_uam",
            "band_iob_uam", "band_iob_decaying_uam",
        }
        self.assertEqual(expected, set(PREDICTORS))
        for name in BAND_PREDICTORS:
            self.assertIn(name, PREDICTORS)

    def test_band_predictors_give_ordered_band(self):
        ctx = make_ctx()
        for name in BAND_PREDICTORS:
            for h in (5, 30, 60, 120):
                f = PREDICTORS[name](ctx, h)
                self.assertIsNotNone(f.low)
                self.assertIsNotNone(f.high)
                self.assertLessEqual(f.low, f.centre)
                self.assertLessEqual(f.centre, f.high)


class SimplePredictorsTest(unittest.TestCase):
    def test_flat(self):
        self.assertEqual(150.0, PREDICTORS["flat"](make_ctx(), 60).centre)

    def test_series_lookups(self):
        ctx = make_ctx()
        # index 6 = 30 minutes
        self.assertAlmostEqual(150 - 24, PREDICTORS["insulin_only"](ctx, 30).centre)
        self.assertAlmostEqual(150 + 12, PREDICTORS["scenario_best"](ctx, 30).centre)
        self.assertAlmostEqual(150 - 6, PREDICTORS["mean_iob_uam"](ctx, 30).centre)
        self.assertAlmostEqual(150.0, PREDICTORS["insulin_only"](ctx, 0).centre)

    def test_uam_fades_to_iob(self):
        ctx = make_ctx()
        iob, uam = 150 - 4 * 6, 150 + 2 * 6
        w = 1 - 30 / 60
        self.assertAlmostEqual(iob + (uam - iob) * w, PREDICTORS["uam_fades_to_iob_60"](ctx, 30).centre)
        # after the fade time only insulin is left
        self.assertAlmostEqual(150 - 4 * 18, PREDICTORS["uam_fades_to_iob_60"](ctx, 90).centre)
        self.assertAlmostEqual(150 - 4 * 18, PREDICTORS["uam_fades_to_iob_90"](ctx, 90).centre)


class TrendTest(unittest.TestCase):
    def test_slope(self):
        self.assertAlmostEqual(-6.0, measured_slope(make_ctx(slope_per_5=-6.0)))

    def test_damped_trend_value(self):
        ctx = make_ctx(slope_per_5=-6.0)
        r = 0.8
        expected = 150 - 6 * sum(r ** j for j in range(1, 7))
        self.assertAlmostEqual(expected, PREDICTORS["damped_trend_r0.8"](ctx, 30).centre)
        self.assertAlmostEqual(150.0, PREDICTORS["damped_trend_r0.8"](ctx, 0).centre)

    def test_trend_needs_points_near_both_ends(self):
        # only the value at the tick: no point near t - 15 min
        ctx = make_ctx(recent=[BgPoint(T0, 150.0)])
        self.assertIsNone(PREDICTORS["damped_trend_r0.9"](ctx, 30))
        # last value 4 minutes old: too far from the tick time
        ctx = make_ctx(recent=[BgPoint(T0 - 15 * MIN, 170.0), BgPoint(T0 - 4 * MIN, 160.0)])
        self.assertIsNone(PREDICTORS["damped_trend_r0.9"](ctx, 30))
        # values 2 minutes off are fine
        ctx = make_ctx(recent=[BgPoint(T0 - 17 * MIN, 170.0), BgPoint(T0 - 2 * MIN, 155.0)])
        self.assertAlmostEqual(-5.0, measured_slope(ctx))

    def test_no_recent_bg(self):
        ctx = make_ctx(recent=[])
        self.assertIsNone(PREDICTORS["damped_trend_r0.7"](ctx, 30))


class DecayingUamTest(unittest.TestCase):
    def test_falling_fades_fast(self):
        ctx = make_ctx(slope_per_5=-6.0)
        iob, uam = 150 - 4 * 12, 150 + 2 * 12
        expected = iob + (uam - iob) * math.exp(-60 / 30)
        self.assertAlmostEqual(expected, PREDICTORS["decaying_uam"](ctx, 60).centre)

    def test_rising_fades_slowly(self):
        ctx = make_ctx(slope_per_5=3.0)
        iob, uam = 150 - 4 * 12, 150 + 2 * 12
        expected = iob + (uam - iob) * math.exp(-60 / 90)
        self.assertAlmostEqual(expected, PREDICTORS["decaying_uam"](ctx, 60).centre)

    def test_unknown_trend_uses_slow_fade(self):
        ctx = make_ctx(recent=[])
        iob, uam = 150 - 4 * 12, 150 + 2 * 12
        expected = iob + (uam - iob) * math.exp(-60 / 90)
        self.assertAlmostEqual(expected, PREDICTORS["decaying_uam"](ctx, 60).centre)


class BandTest(unittest.TestCase):
    def test_band_iob_uam(self):
        f = PREDICTORS["band_iob_uam"](make_ctx(), 30)
        self.assertAlmostEqual(126.0, f.low)
        self.assertAlmostEqual(162.0, f.high)
        self.assertAlmostEqual(144.0, f.centre)

    def test_band_iob_decaying_uam_centre_is_clamped_trend(self):
        # steep fall: the trend goes below the insulin-only edge, so it is clamped to low
        ctx = make_ctx(slope_per_5=-20.0)
        f = PREDICTORS["band_iob_decaying_uam"](ctx, 30)
        self.assertAlmostEqual(126.0, f.low)
        self.assertAlmostEqual(f.low, f.centre)
        upper = 126 + (162 - 126) * math.exp(-30 / 30)
        self.assertAlmostEqual(upper, f.high)

    def test_band_iob_decaying_uam_centre_inside(self):
        ctx = make_ctx(slope_per_5=-4.0)
        f = PREDICTORS["band_iob_decaying_uam"](ctx, 30)
        trend = 150 - 4 * sum(0.8 ** j for j in range(1, 7))
        self.assertLess(f.low, trend)
        self.assertLess(trend, f.high)
        self.assertAlmostEqual(trend, f.centre)

    def test_band_iob_decaying_uam_without_trend_uses_mean(self):
        f = PREDICTORS["band_iob_decaying_uam"](make_ctx(recent=[]), 30)
        self.assertAlmostEqual((f.low + f.high) / 2, f.centre)


class MissingDataTest(unittest.TestCase):
    SERIES_PREDICTORS = [n for n in PREDICTORS if n != "flat" and not n.startswith("damped_trend")]

    def test_missing_series(self):
        for series in ({}, {"IOB": make_series(150, -4)}, {"UAM": make_series(150, 2)},
                       {"IOB": [], "UAM": []}):
            ctx = make_ctx(series=series)
            for name in self.SERIES_PREDICTORS:
                if name == "insulin_only" and series.get("IOB"):
                    continue
                if name == "scenario_best" and series.get("UAM"):
                    continue
                self.assertIsNone(PREDICTORS[name](ctx, 30), "{} {}".format(name, series.keys()))

    def test_short_arrays(self):
        ctx = make_ctx(series={"IOB": make_series(150, -4, 6), "UAM": make_series(150, 2, 6)})
        for name in self.SERIES_PREDICTORS:
            self.assertIsNotNone(PREDICTORS[name](ctx, 25), name)  # index 5 exists
            self.assertIsNone(PREDICTORS[name](ctx, 30), name)  # index 6 does not
        # flat and trend do not need the series
        self.assertIsNotNone(PREDICTORS["flat"](ctx, 30))
        self.assertIsNotNone(PREDICTORS["damped_trend_r0.8"](ctx, 30))


class NoFutureGlucoseTest(unittest.TestCase):
    def test_future_point_changes_nothing(self):
        base = make_ctx(slope_per_5=-6.0)
        # a very different value 1 minute after the tick: nearest to the tick, but in the future
        future = list(base.recent_bg) + [BgPoint(T0 + 1 * MIN, 400.0), BgPoint(T0 + 30 * MIN, 40.0)]
        with_future = make_ctx(slope_per_5=-6.0, recent=future)
        for name, predictor in PREDICTORS.items():
            for h in (0, 5, 15, 30, 60, 90, 120):
                a = predictor(base, h)
                b = predictor(with_future, h)
                self.assertEqual(a, b, "{} at {} min".format(name, h))

    def test_only_future_points_gives_no_trend(self):
        ctx = make_ctx(recent=[BgPoint(T0 + 1 * MIN, 150.0), BgPoint(T0 - 14 * MIN, 170.0)])
        self.assertIsNone(measured_slope(ctx))


if __name__ == "__main__":
    unittest.main()
