"""Score prediction curves against the glucose that was really measured.

Python 3.9, standard library only.

A predictor is a callable ``predictor(context, horizon_min) -> Optional[Forecast]``.
It gets only what was known at the tick (see ``model.Context``) and a horizon in minutes.
It returns ``None`` when it has no answer for that horizon (for example the curve is too short).

Main steps:
1. ``evaluate`` runs every predictor on every tick and horizon and builds one row each.
2. ``summarise`` keeps only the "common rows" (each tick and horizon where every predictor gave
   an answer, so all predictors are compared on the same cases) and turns them into metrics
   (overall and per stratum), hypo detection numbers and an out-of-sample ("conformal") band
   check.
"""

import bisect
import math
import statistics
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Tuple

from zoneinfo import ZoneInfo

from .model import STEP_MS, BgPoint, Context, Dataset, Forecast, Tick

MINUTE_MS = 60 * 1000

# A measured value counts as "at" a time when it is at most this far away.
MATCH_TOLERANCE_MS = 150 * 1000

# Window of measured glucose a predictor may see.
RECENT_WINDOW_MS = 30 * MINUTE_MS

# Window used to decide if glucose is falling, stable or rising at the tick.
TREND_WINDOW_MS = 15 * MINUTE_MS
TREND_LIMIT_PER_5MIN = 2.0

IOB_HIGH_U = 3.0

# Local time groups. "night" is 00:00-05:59, the usual CGM definition of night (it avoids most
# of the dinner). "evening" is 22:00-23:59, kept apart so it is mixed neither with the night
# nor with the morning. "day" is 06:00-21:59.
NIGHT_END_HOUR = 6
EVENING_START_HOUR = 22

HYPO_MGDL = 70.0
HIGH_MGDL = 180.0
ALARM_MGDL = 80.0
NO_ALARM_MGDL = 90.0
BIG_ERROR_MGDL = 40.0

DEFAULT_HORIZONS = (15, 30, 45, 60, 90, 120)

TRAIN_SHARE = 0.7
CONFORMAL_LEVEL = 0.8

# Stratum filters used by ``summarise``. ("all", None) means no filter.
STRATUM_FILTERS: Tuple[Tuple[str, Optional[str]], ...] = (
    ("all", None),
    ("trend", "falling"),
    ("trend", "stable"),
    ("trend", "rising"),
    ("iob", "high"),
    ("iob", "low"),
    ("time", "night"),
    ("time", "evening"),
    ("time", "day"),
    ("outcome", "hypo"),
    ("outcome", "range"),
    ("outcome", "high"),
)

Predictor = Callable[[Context, int], Optional[Forecast]]


@dataclass
class Row:
    """One prediction for one tick, one horizon and one predictor, with the real value."""

    tick_ms: int
    horizon_min: int
    predictor: str
    centre: float
    low: Optional[float]
    high: Optional[float]
    actual: float
    error: float
    strata: Dict[str, str] = field(default_factory=dict)

    @property
    def has_band(self) -> bool:
        return self.low is not None and self.high is not None


# ---------------------------------------------------------------------------------------------
# Alignment and context
# ---------------------------------------------------------------------------------------------


def _times(bg: Sequence[BgPoint]) -> List[int]:
    return [p.time_ms for p in bg]


def actual_at(bg: List[BgPoint], time_ms: int, times: Optional[List[int]] = None) -> Optional[float]:
    """Measured glucose nearest to ``time_ms`` within +-150 s, else ``None``.

    ``bg`` must be sorted by time. ``times`` is an optional cached list of the times of ``bg``.
    When two points are equally near, the earlier one wins.
    """
    if not bg:
        return None
    if times is None:
        times = _times(bg)
    i = bisect.bisect_left(times, time_ms)
    best: Optional[BgPoint] = None
    for j in (i - 1, i):
        if 0 <= j < len(bg):
            p = bg[j]
            if abs(p.time_ms - time_ms) <= MATCH_TOLERANCE_MS:
                if best is None or abs(p.time_ms - time_ms) < abs(best.time_ms - time_ms):
                    best = p
    return None if best is None else best.mgdl


def _points_between(bg: List[BgPoint], times: List[int], start_ms: int, end_ms: int) -> List[BgPoint]:
    """Points with start_ms <= time <= end_ms, oldest first."""
    lo = bisect.bisect_left(times, start_ms)
    hi = bisect.bisect_right(times, end_ms)
    return list(bg[lo:hi])


def context_for(tick: Tick, bg: List[BgPoint], times: Optional[List[int]] = None) -> Context:
    """Build what a predictor may know: the tick and measured glucose of the last 30 minutes.

    No point later than the tick time is included.
    """
    if times is None:
        times = _times(bg)
    recent = _points_between(bg, times, tick.time_ms - RECENT_WINDOW_MS, tick.time_ms)
    return Context(tick=tick, recent_bg=recent)


# ---------------------------------------------------------------------------------------------
# Strata
# ---------------------------------------------------------------------------------------------


def trend_slope(recent_bg: List[BgPoint], tick_ms: int) -> Optional[float]:
    """Average change per 5 minutes over the last 15 minutes, from measured values only.

    Needs a point within 150 s before the tick and a point within 150 s of 15 minutes before.
    Returns ``None`` when that is not possible.
    """
    if not recent_bg:
        return None
    points = [p for p in recent_bg if p.time_ms <= tick_ms]
    if not points:
        return None
    last = points[-1]
    if tick_ms - last.time_ms > MATCH_TOLERANCE_MS:
        return None
    start = tick_ms - TREND_WINDOW_MS
    first: Optional[BgPoint] = None
    for p in points:
        if abs(p.time_ms - start) <= MATCH_TOLERANCE_MS:
            if first is None or abs(p.time_ms - start) < abs(first.time_ms - start):
                first = p
    if first is None or last.time_ms <= first.time_ms:
        return None
    steps = (last.time_ms - first.time_ms) / STEP_MS
    return (last.mgdl - first.mgdl) / steps


def trend_stratum(slope: Optional[float]) -> str:
    if slope is None:
        return "unknown"
    if slope < -TREND_LIMIT_PER_5MIN:
        return "falling"
    if slope > TREND_LIMIT_PER_5MIN:
        return "rising"
    return "stable"


def iob_stratum(iob_u: Optional[float]) -> str:
    if iob_u is None:
        return "unknown"
    return "high" if iob_u >= IOB_HIGH_U else "low"


def time_stratum(time_ms: int, tz_name: str) -> str:
    """Local time group: "night" (00-05 h), "evening" (22-23 h) or "day" (06-21 h)."""
    local = datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc).astimezone(ZoneInfo(tz_name))
    if local.hour < NIGHT_END_HOUR:
        return "night"
    if local.hour >= EVENING_START_HOUR:
        return "evening"
    return "day"


def outcome_stratum(actual: float) -> str:
    if actual < HYPO_MGDL:
        return "hypo"
    if actual > HIGH_MGDL:
        return "high"
    return "range"


# ---------------------------------------------------------------------------------------------
# Evaluation
# ---------------------------------------------------------------------------------------------


def evaluate(
    dataset: Dataset,
    predictors: Dict[str, Predictor],
    horizons: Iterable[int] = DEFAULT_HORIZONS,
) -> List[Row]:
    """Run every predictor on every tick and horizon. Skip a row when there is no real value
    or the predictor gives no answer."""
    horizons = tuple(horizons)
    times = _times(dataset.bg)
    rows: List[Row] = []
    for tick in dataset.ticks:
        ctx = context_for(tick, dataset.bg, times)
        tick_strata = {
            "trend": trend_stratum(trend_slope(ctx.recent_bg, tick.time_ms)),
            "iob": iob_stratum(tick.iob_u),
            "time": time_stratum(tick.time_ms, dataset.tz_name),
        }
        for h in horizons:
            actual = actual_at(dataset.bg, tick.time_ms + h * MINUTE_MS, times)
            if actual is None:
                continue
            strata = dict(tick_strata)
            strata["outcome"] = outcome_stratum(actual)
            for name, predictor in predictors.items():
                fc = predictor(ctx, h)
                if fc is None or fc.centre is None or math.isnan(fc.centre):
                    continue
                rows.append(
                    Row(
                        tick_ms=tick.time_ms,
                        horizon_min=h,
                        predictor=name,
                        centre=fc.centre,
                        low=fc.low,
                        high=fc.high,
                        actual=actual,
                        error=fc.centre - actual,
                        strata=strata,
                    )
                )
    return rows


# ---------------------------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------------------------


def _metrics(rows: List[Row]) -> Dict[str, Optional[float]]:
    errors = [r.error for r in rows]
    n = len(errors)
    out: Dict[str, Optional[float]] = {
        "n": n,
        "bias": None,
        "mae": None,
        "rmse": None,
        "share_big_error": None,
        "band_n": 0,
        "coverage": None,
        "mean_width": None,
    }
    if n == 0:
        return out
    out["bias"] = statistics.fmean(errors)
    out["mae"] = statistics.fmean(abs(e) for e in errors)
    out["rmse"] = math.sqrt(statistics.fmean(e * e for e in errors))
    out["share_big_error"] = sum(1 for e in errors if abs(e) > BIG_ERROR_MGDL) / n
    band = [r for r in rows if r.has_band]
    if band:
        out["band_n"] = len(band)
        out["coverage"] = sum(1 for r in band if r.low <= r.actual <= r.high) / len(band)
        out["mean_width"] = statistics.fmean(r.high - r.low for r in band)
    return out


def _alarm_value(row: Row) -> float:
    """Value used for a low alarm: the low edge for a band, else the centre."""
    return row.low if row.has_band else row.centre


def hypo_detection(rows: List[Row]) -> List[Dict[str, object]]:
    """Per (predictor, horizon): sensitivity and false alarm rate of "predicted < 80"."""
    groups: Dict[Tuple[str, int], List[Row]] = {}
    for r in rows:
        groups.setdefault((r.predictor, r.horizon_min), []).append(r)
    result = []
    for (name, h), group in sorted(groups.items()):
        hypo = [r for r in group if r.actual < HYPO_MGDL]
        safe = [r for r in group if r.actual >= NO_ALARM_MGDL]
        caught = sum(1 for r in hypo if _alarm_value(r) < ALARM_MGDL)
        false = sum(1 for r in safe if _alarm_value(r) < ALARM_MGDL)
        result.append(
            {
                "predictor": name,
                "horizon_min": h,
                "n_hypo": len(hypo),
                "sensitivity": caught / len(hypo) if hypo else None,
                "n_no_hypo": len(safe),
                "false_alarm_rate": false / len(safe) if safe else None,
            }
        )
    return result


def nearest_rank_quantile(values: List[float], level: float) -> float:
    """Nearest-rank quantile: the smallest value with at least ``level`` of the data at or below."""
    if not values:
        raise ValueError("no values")
    ordered = sorted(values)
    k = max(1, math.ceil(level * len(ordered)))
    return ordered[k - 1]


def split_time(rows: List[Row], train_share: float = TRAIN_SHARE) -> Optional[int]:
    """Tick time where the test part starts. Ticks before it are train, the rest are test.

    The split is on distinct tick times, so one tick never sits on both sides.
    """
    tick_times = sorted({r.tick_ms for r in rows})
    if len(tick_times) < 2:
        return None
    cut = int(math.floor(train_share * len(tick_times)))
    cut = min(max(cut, 1), len(tick_times) - 1)
    return tick_times[cut]


def conformal(rows: List[Row], level: float = CONFORMAL_LEVEL, train_share: float = TRAIN_SHARE) -> List[Dict[str, object]]:
    """Out-of-sample band around each predictor's centre.

    q is the ``level`` quantile of the absolute errors on the train part (the first 70 % of
    ticks by time). On the test part the band is [centre - q, centre + q]. Test numbers never
    use test errors to set q.

    Embargo: a train row is used only when its real glucose was measured before the split
    (tick time + horizon < split). Without this, a tick just before the split would learn from
    glucose of the test period.
    """
    split = split_time(rows, train_share)
    if split is None:
        return []
    groups: Dict[Tuple[str, int], List[Row]] = {}
    for r in rows:
        groups.setdefault((r.predictor, r.horizon_min), []).append(r)
    result = []
    for (name, h), group in sorted(groups.items()):
        train = [r for r in group if r.tick_ms + r.horizon_min * MINUTE_MS < split]
        test = [r for r in group if r.tick_ms >= split]
        entry: Dict[str, object] = {
            "predictor": name,
            "horizon_min": h,
            "split_ms": split,
            "n_train": len(train),
            "n_test": len(test),
            "q": None,
            "coverage": None,
            "width": None,
            "test_mae": None,
            "test_bias": None,
        }
        if test:
            entry["test_mae"] = statistics.fmean(abs(r.error) for r in test)
            entry["test_bias"] = statistics.fmean(r.error for r in test)
        if train:
            q = nearest_rank_quantile([abs(r.error) for r in train], level)
            entry["q"] = q
            entry["width"] = 2 * q
            if test:
                entry["coverage"] = sum(1 for r in test if r.centre - q <= r.actual <= r.centre + q) / len(test)
        result.append(entry)
    return result


def common_rows(rows: List[Row]) -> Tuple[List[Row], List[Dict[str, object]]]:
    """Keep, per horizon, only the ticks where every predictor seen at that horizon answered.

    Some predictors give no answer on some ticks (for example the damped trend when the
    15-minute slope is unknown). Without this filter they would be scored on easier cases than
    the others. Returns (kept rows, one entry per horizon with the counts).
    """
    names_by_h: Dict[int, set] = {}
    answered: Dict[Tuple[int, int], set] = {}
    for r in rows:
        names_by_h.setdefault(r.horizon_min, set()).add(r.predictor)
        answered.setdefault((r.horizon_min, r.tick_ms), set()).add(r.predictor)
    kept = [r for r in rows if answered[(r.horizon_min, r.tick_ms)] >= names_by_h[r.horizon_min]]
    info: List[Dict[str, object]] = []
    for h in sorted(names_by_h):
        ticks = [t for (hh, t) in answered if hh == h]
        full = [t for t in ticks if answered[(h, t)] >= names_by_h[h]]
        info.append({
            "horizon_min": h,
            "n_predictors": len(names_by_h[h]),
            "ticks_scored": len(ticks),
            "ticks_common": len(full),
            "ticks_dropped": len(ticks) - len(full),
        })
    return kept, info


def summarise(rows: List[Row]) -> Dict[str, List[Dict[str, object]]]:
    """Return {"metrics": [...], "hypo": [...], "conformal": [...], "common": [...]}.

    All three use only the common rows (see ``common_rows``); "common" says how many ticks
    were dropped per horizon. Each metrics entry has predictor, horizon_min, stratum ("all" or
    e.g. "trend=falling"), n, bias, mae, rmse, share_big_error, band_n, coverage, mean_width.
    """
    rows, common_info = common_rows(rows)
    groups: Dict[Tuple[str, int], List[Row]] = {}
    for r in rows:
        groups.setdefault((r.predictor, r.horizon_min), []).append(r)
    metrics: List[Dict[str, object]] = []
    for (name, h), group in sorted(groups.items()):
        for key, value in STRATUM_FILTERS:
            if key == "all":
                subset = group
                label = "all"
            else:
                subset = [r for r in group if r.strata.get(key) == value]
                label = "%s=%s" % (key, value)
            entry: Dict[str, object] = {"predictor": name, "horizon_min": h, "stratum": label}
            entry.update(_metrics(subset))
            metrics.append(entry)
    return {"metrics": metrics, "hypo": hypo_detection(rows), "conformal": conformal(rows),
            "common": common_info}
