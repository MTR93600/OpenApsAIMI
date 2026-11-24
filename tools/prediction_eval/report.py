"""Write the evaluation results as a Markdown report and a CSV file.

Python 3.9, standard library only.

Privacy: the report and the CSV hold health data. Write them to a local folder outside the
repository only. Never commit them.
"""

import csv
import os
from datetime import datetime, timezone
from typing import Dict, List, Optional, Sequence

from zoneinfo import ZoneInfo

from .model import Dataset
from .scoring import (
    ALARM_MGDL,
    BIG_ERROR_MGDL,
    CONFORMAL_LEVEL,
    EVENING_START_HOUR,
    HYPO_MGDL,
    IOB_HIGH_U,
    NIGHT_END_HOUR,
    NO_ALARM_MGDL,
    TRAIN_SHARE,
    Row,
)

REPORT_NAME = "prediction_eval_report.md"
ROWS_NAME = "prediction_eval_rows.csv"


def _fmt(value: Optional[float], digits: int = 1) -> str:
    if value is None:
        return "-"
    return "%.*f" % (digits, value)


def _pct(value: Optional[float]) -> str:
    if value is None:
        return "-"
    return "%.0f %%" % (100.0 * value)


def _local_time(time_ms: int, tz_name: str) -> str:
    dt = datetime.fromtimestamp(time_ms / 1000.0, tz=timezone.utc).astimezone(ZoneInfo(tz_name))
    return dt.strftime("%Y-%m-%d %H:%M")


def _table(header: Sequence[str], lines: List[Sequence[str]]) -> List[str]:
    out = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    for line in lines:
        out.append("| " + " | ".join(line) + " |")
    return out


def _horizons(summary: Dict[str, List[Dict[str, object]]]) -> List[int]:
    return sorted({int(m["horizon_min"]) for m in summary.get("metrics", [])})


def _ranking_section(summary: Dict[str, List[Dict[str, object]]], stratum: str, title: str) -> List[str]:
    out = ["## " + title, ""]
    metrics = [m for m in summary.get("metrics", []) if m["stratum"] == stratum and m["n"]]
    if not metrics:
        out += ["No data for this group.", ""]
        return out
    for h in _horizons(summary):
        group = [m for m in metrics if m["horizon_min"] == h]
        if not group:
            continue
        group.sort(key=lambda m: m["mae"])
        lines = []
        for rank, m in enumerate(group, start=1):
            lines.append(
                [
                    str(rank),
                    str(m["predictor"]),
                    str(m["n"]),
                    _fmt(m["mae"]),
                    _fmt(m["bias"]),
                    _fmt(m["rmse"]),
                    _pct(m["share_big_error"]),
                ]
            )
        out.append("### In %d min" % h)
        out.append("")
        out += _table(
            ["Rank", "Predictor", "n", "MAE", "Bias", "RMSE", "|error| > %d" % BIG_ERROR_MGDL],
            lines,
        )
        out.append("")
    return out


def _band_section(summary: Dict[str, List[Dict[str, object]]]) -> List[str]:
    out = ["## Bands (all data)", ""]
    bands = [m for m in summary.get("metrics", []) if m["stratum"] == "all" and m["band_n"]]
    if not bands:
        out += ["No predictor gave a band.", ""]
        return out
    out.append("Coverage is the share of times the real glucose was inside the band. "
               "A narrow band with good coverage is best.")
    out.append("")
    bands.sort(key=lambda m: (m["horizon_min"], str(m["predictor"])))
    lines = [
        [str(m["horizon_min"]), str(m["predictor"]), str(m["band_n"]), _pct(m["coverage"]), _fmt(m["mean_width"])]
        for m in bands
    ]
    out += _table(["Horizon (min)", "Predictor", "n", "Coverage", "Mean width"], lines)
    out.append("")
    return out


def _conformal_section(summary: Dict[str, List[Dict[str, object]]], tz_name: str) -> List[str]:
    out = ["## Calibrated band, test part only", ""]
    entries = list(summary.get("conformal", []))
    if not entries:
        out += ["Not enough ticks to split the data.", ""]
        return out
    split = entries[0]["split_ms"]
    out.append(
        "The first %d %% of ticks (by time) are used to learn the band width. "
        "The numbers below come only from the last %d %%, which starts at %s. "
        "The band is the centre plus or minus q, where q is the %d %% quantile of the "
        "absolute errors in the first part. In the first part, a prediction is used only when its "
        "real glucose was measured before the split, so no glucose of the test part is used to "
        "learn q. A good band covers about %d %% of the test part."
        % (
            round(100 * TRAIN_SHARE),
            round(100 * (1 - TRAIN_SHARE)),
            _local_time(int(split), tz_name),
            round(100 * CONFORMAL_LEVEL),
            round(100 * CONFORMAL_LEVEL),
        )
    )
    out.append("")
    entries.sort(key=lambda e: (e["horizon_min"], e["test_mae"] if e["test_mae"] is not None else 1e9))
    lines = [
        [
            str(e["horizon_min"]),
            str(e["predictor"]),
            str(e["n_train"]),
            str(e["n_test"]),
            _fmt(e["test_mae"]),
            _fmt(e["test_bias"]),
            _fmt(e["q"]),
            _pct(e["coverage"]),
            _fmt(e["width"]),
        ]
        for e in entries
    ]
    out += _table(
        ["Horizon (min)", "Predictor", "n train", "n test", "Test MAE", "Test bias", "q", "Test coverage", "Width"],
        lines,
    )
    out.append("")
    return out


def _hypo_section(summary: Dict[str, List[Dict[str, object]]]) -> List[str]:
    out = ["## Low glucose detection", ""]
    out.append(
        "A predictor \"warns\" when its value (the low edge for a band) is below %d mg/dL. "
        "Sensitivity: among times when the real glucose was below %d, the share with a warning. "
        "False alarms: among times when the real glucose was %d or more, the share with a warning."
        % (ALARM_MGDL, HYPO_MGDL, NO_ALARM_MGDL)
    )
    out.append("")
    entries = sorted(summary.get("hypo", []), key=lambda e: (e["horizon_min"], str(e["predictor"])))
    if not entries:
        out += ["No data.", ""]
        return out
    lines = [
        [
            str(e["horizon_min"]),
            str(e["predictor"]),
            str(e["n_hypo"]),
            _pct(e["sensitivity"]),
            str(e["n_no_hypo"]),
            _pct(e["false_alarm_rate"]),
        ]
        for e in entries
    ]
    out += _table(["Horizon (min)", "Predictor", "n low", "Sensitivity", "n not low", "False alarms"], lines)
    out.append("")
    return out


def _common_section(summary: Dict[str, List[Dict[str, object]]]) -> List[str]:
    entries = summary.get("common", [])
    if not entries:
        return []
    out = [
        "## Same cases for every predictor",
        "",
        "All tables below use only the ticks where every predictor gave an answer at that horizon, "
        "so the predictors are compared on the same cases. Dropped ticks are left out of every table.",
        "",
    ]
    lines = [
        [str(e["horizon_min"]), str(e["n_predictors"]), str(e["ticks_scored"]), str(e["ticks_common"]),
         str(e["ticks_dropped"])]
        for e in entries
    ]
    out += _table(["Horizon (min)", "Predictors", "Ticks with a real value", "Ticks kept", "Ticks dropped"],
                  lines)
    out.append("")
    return out


def _how_to_read() -> List[str]:
    return [
        "## How to read this",
        "",
        "- MAE is the mean absolute error in mg/dL. Lower is better.",
        "- Bias is the mean of (predicted - real). Above 0 means the curve is too high on average.",
        "- The loop curves (IOB, UAM, COB, ZT) are conditional scenarios. They say what would happen "
        "if the loop did nothing more. The loop then acts on them, so the real glucose moves away "
        "from them on purpose. This is why a flat line (\"nothing moves\") can win. A curve that "
        "loses here can still be a good curve for dosing.",
        "- This report only compares curves for display. It does not judge the dosing.",
        "- Time groups use the local time of the tick: night is 00:00-%02d:59, evening is "
        "%02d:00-23:59, day is the rest." % (NIGHT_END_HOUR - 1, EVENING_START_HOUR),
        "- One day of data is not enough to choose. Look at several weeks and at each group "
        "(night, falling, rising) before deciding.",
        "- This is a technical measurement, not medical advice.",
        "",
    ]


def write_rows_csv(rows: List[Row], path: str) -> str:
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(
            ["tick_ms", "horizon_min", "predictor", "centre", "low", "high", "actual", "error",
             "trend", "iob", "time", "outcome"]
        )
        for r in rows:
            writer.writerow(
                [
                    r.tick_ms,
                    r.horizon_min,
                    r.predictor,
                    "%.2f" % r.centre,
                    "" if r.low is None else "%.2f" % r.low,
                    "" if r.high is None else "%.2f" % r.high,
                    "%.2f" % r.actual,
                    "%.2f" % r.error,
                    r.strata.get("trend", ""),
                    r.strata.get("iob", ""),
                    r.strata.get("time", ""),
                    r.strata.get("outcome", ""),
                ]
            )
    return path


def write_report(
    dataset: Dataset,
    rows: List[Row],
    summary: Dict[str, List[Dict[str, object]]],
    out_dir: str,
) -> str:
    """Write the Markdown report and the CSV of rows into ``out_dir``. Return the report path."""
    os.makedirs(out_dir, exist_ok=True)
    tz = dataset.tz_name
    lines: List[str] = ["# Prediction evaluation", ""]

    if dataset.ticks:
        first = min(t.time_ms for t in dataset.ticks)
        last = max(t.time_ms for t in dataset.ticks)
        lines.append("- Data from %s to %s (%s)." % (_local_time(first, tz), _local_time(last, tz), tz))
    else:
        lines.append("- No ticks in the data.")
    lines.append("- Number of ticks: %d." % len(dataset.ticks))
    lines.append("- Number of measured glucose values: %d." % len(dataset.bg))
    lines.append("- Number of scored predictions: %d." % len(rows))
    lines.append("")

    if dataset.notes:
        lines += ["## Notes about the data", ""]
        lines += ["- " + note for note in dataset.notes]
        lines.append("")

    lines += _how_to_read()
    lines += _common_section(summary)
    lines += _ranking_section(summary, "all", "Ranking by MAE, all data")
    lines += _ranking_section(summary, "trend=falling", "Ranking by MAE, glucose falling")
    lines += _ranking_section(summary, "trend=rising", "Ranking by MAE, glucose rising")
    lines += _ranking_section(summary, "time=night",
                              "Ranking by MAE, night (00:00-%02d:59 local)" % (NIGHT_END_HOUR - 1))
    lines += _ranking_section(summary, "time=evening",
                              "Ranking by MAE, evening (%02d:00-23:59 local)" % EVENING_START_HOUR)
    lines += _ranking_section(summary, "time=day",
                              "Ranking by MAE, day (%02d:00-%02d:59 local)" % (NIGHT_END_HOUR, EVENING_START_HOUR - 1))
    lines += _ranking_section(summary, "iob=high", "Ranking by MAE, IOB %.0f U or more" % IOB_HIGH_U)
    lines += _band_section(summary)
    lines += _conformal_section(summary, tz)
    lines += _hypo_section(summary)

    report_path = os.path.join(out_dir, REPORT_NAME)
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    write_rows_csv(rows, os.path.join(out_dir, ROWS_NAME))
    return report_path
