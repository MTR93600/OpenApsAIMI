"""Shared data model for the prediction evaluation tool.

Every module of the tool talks through these types only. Python 3.9, standard library only.

Units: glucose in mg/dL, time in epoch milliseconds (UTC). Prediction series are the AAPS
``predBGs`` arrays: index 0 is the glucose at the tick, index i is i * 5 minutes later.

Privacy: these records hold health data. They are read from local files and written to local
reports only. Never commit real data or reports to the repository.
"""

from dataclasses import dataclass, field
from typing import Dict, List, Optional

STEP_MS = 5 * 60 * 1000


@dataclass(frozen=True)
class BgPoint:
    """One measured glucose value."""

    time_ms: int
    mgdl: float


@dataclass
class Tick:
    """One loop decision with the predictions it published.

    ``series`` maps a series name ("IOB", "UAM", "COB", "ZT", "aCOB") to its predBGs array.
    In AIMI with the prediction authority on, "IOB" is the insulin-only PK/PD curve
    (clinicalFloor) and "UAM" is scenarioBest, the dose-facing curve.
    """

    time_ms: int
    bg: float
    series: Dict[str, List[float]]
    iob_u: Optional[float] = None
    eventual_bg: Optional[float] = None
    source: str = ""


@dataclass
class Dataset:
    """Ticks and the measured glucose used as ground truth, both sorted by time."""

    ticks: List[Tick] = field(default_factory=list)
    bg: List[BgPoint] = field(default_factory=list)
    tz_name: str = "UTC"
    notes: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class Context:
    """What a predictor may know at the tick. Nothing from after ``tick.time_ms``.

    ``recent_bg`` holds measured values up to and including the tick time, oldest first.
    """

    tick: Tick
    recent_bg: List[BgPoint]


@dataclass(frozen=True)
class Forecast:
    """A predictor's answer for one horizon: a centre value and an optional band."""

    centre: float
    low: Optional[float] = None
    high: Optional[float] = None
