"""Candidate prediction lines that a home screen could show.

Each predictor is a function ``(ctx, horizon_min) -> Optional[Forecast]``. It may use only
``ctx``: the tick and the glucose measured up to the tick time. It never looks at later glucose.

``horizon_min`` is a multiple of 5. The predBGs index for it is ``horizon_min // 5``.
A predictor returns None when the data it needs is missing or too short.

Python 3.9, standard library only.
"""

import math
from typing import Callable, Dict, List, Optional

from .model import BgPoint, Context, Forecast, STEP_MS

Predictor = Callable[[Context, int], Optional[Forecast]]

# How far a measured value may be from the time we want (for the slope).
SLOPE_TOLERANCE_MS = 3 * 60 * 1000
# The slope looks back this far.
SLOPE_WINDOW_MS = 15 * 60 * 1000

DAMPING_FACTORS = (0.7, 0.8, 0.85, 0.9)
FADE_TIMES_MIN = (60, 90)
# Time constants (minutes) for the UAM excess to fade in decaying_uam.
FADE_WHEN_FALLING_MIN = 30.0
FADE_WHEN_RISING_MIN = 90.0
# The damping factor used for the centre of band_iob_decaying_uam.
BAND_CENTRE_DAMPING = 0.8


# ---------------------------------------------------------------- helpers


def _series_value(ctx: Context, name: str, horizon_min: int) -> Optional[float]:
    """Value of a predBGs series at the horizon, or None if missing or too short."""
    values = ctx.tick.series.get(name)
    if not values:
        return None
    index = horizon_min // 5
    if index < 0 or index >= len(values):
        return None
    value = values[index]
    if value is None:
        return None
    return float(value)


def _known_bg(ctx: Context) -> List[BgPoint]:
    """Measured values at or before the tick time. A later value is ignored, as a safety net."""
    return [p for p in ctx.recent_bg if p.time_ms <= ctx.tick.time_ms]


def _nearest(points: List[BgPoint], time_ms: int) -> Optional[BgPoint]:
    """The measured value nearest to time_ms, if it is within the tolerance."""
    best = None
    for p in points:
        if best is None or abs(p.time_ms - time_ms) < abs(best.time_ms - time_ms):
            best = p
    if best is None or abs(best.time_ms - time_ms) > SLOPE_TOLERANCE_MS:
        return None
    return best


def measured_slope(ctx: Context) -> Optional[float]:
    """Average glucose change per 5 minutes over the last 15 minutes, or None if unknown."""
    points = _known_bg(ctx)
    now = ctx.tick.time_ms
    end = _nearest(points, now)
    start = _nearest(points, now - SLOPE_WINDOW_MS)
    if end is None or start is None or end.time_ms <= start.time_ms:
        return None
    steps = (end.time_ms - start.time_ms) / STEP_MS
    return (end.mgdl - start.mgdl) / steps


def _damped_centre(ctx: Context, horizon_min: int, r: float) -> Optional[float]:
    slope = measured_slope(ctx)
    if slope is None:
        return None
    n = horizon_min // 5
    total = sum(r ** j for j in range(1, n + 1))
    return ctx.tick.bg + slope * total


def _decaying_uam_value(ctx: Context, horizon_min: int) -> Optional[float]:
    iob = _series_value(ctx, "IOB", horizon_min)
    uam = _series_value(ctx, "UAM", horizon_min)
    if iob is None or uam is None:
        return None
    slope = measured_slope(ctx)
    tau = FADE_WHEN_FALLING_MIN if slope is not None and slope < 0 else FADE_WHEN_RISING_MIN
    w = math.exp(-horizon_min / tau)
    return iob + (uam - iob) * w


# ---------------------------------------------------------------- predictors


def flat(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """Nothing moves: glucose stays where it is now."""
    return Forecast(centre=float(ctx.tick.bg))


def insulin_only(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """Only insulin acts: nothing more is absorbed (the "IOB" curve)."""
    value = _series_value(ctx, "IOB", horizon_min)
    return None if value is None else Forecast(centre=value)


def scenario_best(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """The "UAM" curve, which the home screen shows today (AIMI scenarioBest)."""
    value = _series_value(ctx, "UAM", horizon_min)
    return None if value is None else Forecast(centre=value)


def mean_iob_uam(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """Half way between "nothing absorbed" and "absorption goes on"."""
    iob = _series_value(ctx, "IOB", horizon_min)
    uam = _series_value(ctx, "UAM", horizon_min)
    if iob is None or uam is None:
        return None
    return Forecast(centre=(iob + uam) / 2.0)


def make_damped_trend(r: float) -> Predictor:
    """Build a predictor that follows the measured trend, which slows down by factor r each 5 min."""

    def damped_trend(ctx: Context, horizon_min: int) -> Optional[Forecast]:
        """The present trend goes on but fades a little every 5 minutes."""
        value = _damped_centre(ctx, horizon_min, r)
        return None if value is None else Forecast(centre=value)

    return damped_trend


def make_uam_fades_to_iob(fade_min: int) -> Predictor:
    """Build a predictor that moves from UAM to IOB in a straight line over fade_min minutes."""

    def uam_fades_to_iob(ctx: Context, horizon_min: int) -> Optional[Forecast]:
        """Absorption is believed now, less and less later; after fade_min only insulin acts."""
        iob = _series_value(ctx, "IOB", horizon_min)
        uam = _series_value(ctx, "UAM", horizon_min)
        if iob is None or uam is None:
            return None
        w = max(0.0, 1.0 - horizon_min / float(fade_min))
        return Forecast(centre=iob + (uam - iob) * w)

    return uam_fades_to_iob


def decaying_uam(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """UAM whose extra rise fades fast if glucose is falling now (30 min), slowly if not (90 min)."""
    value = _decaying_uam_value(ctx, horizon_min)
    return None if value is None else Forecast(centre=value)


def band_iob_uam(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """Band from the IOB curve to the UAM curve, centre in the middle."""
    iob = _series_value(ctx, "IOB", horizon_min)
    uam = _series_value(ctx, "UAM", horizon_min)
    if iob is None or uam is None:
        return None
    return Forecast(centre=(iob + uam) / 2.0, low=min(iob, uam), high=max(iob, uam))


def band_iob_decaying_uam(ctx: Context, horizon_min: int) -> Optional[Forecast]:
    """Low edge "nothing absorbed", high edge "absorption goes on but fades"; centre is the trend."""
    iob = _series_value(ctx, "IOB", horizon_min)
    upper = _decaying_uam_value(ctx, horizon_min)
    if iob is None or upper is None:
        return None
    low = min(iob, upper)
    high = max(iob, upper)
    trend = _damped_centre(ctx, horizon_min, BAND_CENTRE_DAMPING)
    if trend is None:
        centre = (low + high) / 2.0
    else:
        centre = min(max(trend, low), high)
    return Forecast(centre=centre, low=low, high=high)


# ---------------------------------------------------------------- registry


def _build_registry() -> Dict[str, Predictor]:
    registry: Dict[str, Predictor] = {
        "flat": flat,
        "insulin_only": insulin_only,
        "scenario_best": scenario_best,
        "mean_iob_uam": mean_iob_uam,
    }
    for r in DAMPING_FACTORS:
        registry["damped_trend_r{}".format(r)] = make_damped_trend(r)
    for fade_min in FADE_TIMES_MIN:
        registry["uam_fades_to_iob_{}".format(fade_min)] = make_uam_fades_to_iob(fade_min)
    registry["decaying_uam"] = decaying_uam
    registry["band_iob_uam"] = band_iob_uam
    registry["band_iob_decaying_uam"] = band_iob_decaying_uam
    return registry


PREDICTORS: Dict[str, Predictor] = _build_registry()

BAND_PREDICTORS: List[str] = ["band_iob_uam", "band_iob_decaying_uam"]
