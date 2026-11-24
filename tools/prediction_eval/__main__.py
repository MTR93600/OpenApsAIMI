"""Command line entry point.

Run from the repository root:

    python3 -m tools.prediction_eval --aaps-log FILE [FILE ...] --tz Europe/Paris --out DIR
    python3 -m tools.prediction_eval --ns-devicestatus FILE --ns-entries FILE --tz Europe/Paris --out DIR

The report holds health data. ``--out`` must be a folder outside the repository.

Python 3.9, standard library only.
"""

import argparse
import sys
from typing import List, Optional

from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .fetch_ns import _is_inside, _repo_root
from .loaders import load_aaps_logs, load_ns_export
from .model import Dataset
from .predictors import PREDICTORS
from .report import write_report
from .scoring import DEFAULT_HORIZONS, evaluate, summarise

# Series that AIMI publishes as copies of another one. We check this on the data and say so.
_COPY_CHECKS = (("COB", "IOB"), ("ZT", "IOB"))


def parse_horizons(text: str) -> List[int]:
    """Read "15,30,60" into [15, 30, 60]. Each value must be a positive multiple of 5."""
    values: List[int] = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        value = int(part)
        if value <= 0 or value % 5 != 0:
            raise ValueError("horizon must be a positive multiple of 5 minutes: {}".format(value))
        values.append(value)
    if not values:
        raise ValueError("no horizon given")
    return sorted(set(values))


def series_copy_notes(dataset: Dataset) -> List[str]:
    """Say how often a series is an exact copy of another one (for example COB == IOB)."""
    notes: List[str] = []
    for name, other in _COPY_CHECKS:
        both = [t for t in dataset.ticks if name in t.series and other in t.series]
        if not both:
            continue
        same = sum(1 for t in both if t.series[name] == t.series[other])
        notes.append("Series check: {} is an exact copy of {} on {} of {} ticks that have both.".format(
            name, other, same, len(both)))
    return notes


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python3 -m tools.prediction_eval",
        description="Compare displayed glucose predictions with the glucose that was really measured.",
    )
    parser.add_argument("--aaps-log", nargs="+", metavar="FILE",
                        help="AAPS log files (iOS or Android, text or zip).")
    parser.add_argument("--ns-devicestatus", metavar="FILE", help="Nightscout devicestatus export (JSON).")
    parser.add_argument("--ns-entries", metavar="FILE", help="Nightscout entries export (JSON).")
    parser.add_argument("--tz", default="UTC", help="Time zone of the phone, for example Europe/Paris.")
    parser.add_argument("--out", required=True, metavar="DIR",
                        help="Folder for the report and CSV. Must be outside the repository.")
    parser.add_argument("--horizons", default=",".join(str(h) for h in DEFAULT_HORIZONS),
                        help="Horizons in minutes, comma separated (default %(default)s).")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    use_log = bool(args.aaps_log)
    use_ns = bool(args.ns_devicestatus or args.ns_entries)
    if use_log == use_ns:
        parser.error("give either --aaps-log, or both --ns-devicestatus and --ns-entries")
    if use_ns and not (args.ns_devicestatus and args.ns_entries):
        parser.error("--ns-devicestatus and --ns-entries must be given together")
    try:
        ZoneInfo(args.tz)
    except (ZoneInfoNotFoundError, ValueError):
        parser.error("unknown time zone: {}".format(args.tz))
    try:
        horizons = parse_horizons(args.horizons)
    except ValueError as error:
        parser.error(str(error))
    if _is_inside(args.out, _repo_root()):
        parser.error("--out is inside the repository. Reports hold health data; write them elsewhere.")

    if use_log:
        dataset = load_aaps_logs(args.aaps_log, args.tz)
    else:
        dataset = load_ns_export(args.ns_devicestatus, args.ns_entries, args.tz)
    dataset.notes.extend(series_copy_notes(dataset))

    if not dataset.ticks:
        print("No loop results with predictions were found. Nothing to score.", file=sys.stderr)
        return 1

    rows = evaluate(dataset, PREDICTORS, horizons)
    summary = summarise(rows)
    report_path = write_report(dataset, rows, summary, args.out)
    # Print counts and paths only, never health values.
    print("Ticks: {}, measured values: {}, scored predictions: {}.".format(
        len(dataset.ticks), len(dataset.bg), len(rows)))
    print("Report: {}".format(report_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
