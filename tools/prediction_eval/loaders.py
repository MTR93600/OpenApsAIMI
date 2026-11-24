"""Read loop results and measured glucose from AAPS logs or Nightscout exports.

Two entry points, both return a ``Dataset`` sorted by time:

* ``load_aaps_logs(paths, tz_name)`` reads AAPS log files (iOS or Android, plain text or zip).
* ``load_ns_export(devicestatus_json_path, entries_json_path, tz_name)`` reads Nightscout API v1
  exports, for example the files written by ``fetch_ns.py``.

Python 3.9, standard library only. The data is health data: read it from local files, write
results only to local places outside the repository.

Log formats
-----------

iOS ``aaps.log``: one entry per line, ``2026-01-02T03:04:05Z LEVEL TAG: message``. The time is
UTC, with second precision. A message can hold real line breaks, so some lines have no time at
the start; they belong to the entry above.

Android ``AndroidAPS.log`` (``app/src/main/assets/logback.xml``): the pattern is
``%d{HH:mm:ss.SSS} [%thread] %.-1level/%logger: %msg``, so a line looks like
``03:04:05.678 [main] D/APS: [Class.method():12]: message``. The line has a time of day but no
date and no zone. We assume the time is the phone's local time in ``tz_name``, and take the date
from the file name (``AndroidAPS._2026-01-02_...`` in the rotated zip files). If the name has no
date, we use the date of the first absolute time found in the file. When the time of day goes
back by more than 12 hours, we assume the day changed.

The line time is only a fallback. Loop results carry their own ``timestamp`` and glucose records
carry an epoch ``timestamp=``, so the loader does not depend on the line time when those exist.

What the loader reads in a log
------------------------------

* Loop results: every JSON object written after ``suggested=`` or ``enacted=`` (Kotlin
  ``toString`` of a DeviceStatus, or of an NSDeviceStatus downloaded from Nightscout), or after
  ``"suggested":`` / ``"enacted":`` (JSON form). Only objects with ``predBGs`` become ticks. The
  same result is often written 2 or 3 times (inserted, nsId updated, downloaded again), so ticks
  are merged by their ``timestamp``.
* The IOB of a tick: the ``"IOB"`` number in the result, else the ``iob={"iob":...}`` object that
  follows it in the same status.
* Measured glucose, best source first:
  1. database records ``GlucoseValue(id=..., isValid=true, ..., timestamp=<ms>, ..., value=<mg/dL>)``;
  2. AUTOSENS lines ``InMemoryGlucoseValue(timestamp=<ms>, value=<mg/dL>, ..., filledGap=false)``.
     These repeat the stored readings and often go further back in time. Values with
     ``filledGap=true`` are made up by interpolation and are skipped.
  The same reading is often in both, with times 1 or 2 seconds apart. An AUTOSENS value that has
  a stored value less than ``SAME_BG_MS`` away is a copy: it is dropped, and compared with that
  stored value. If neither is found, the tick's own glucose is used, and ``Dataset.notes`` says so.
"""

import bisect
import io
import json
import os
import re
import zipfile
from datetime import date, datetime, time, timedelta, timezone
from typing import Dict, Iterable, Iterator, List, Optional, Tuple

from zoneinfo import ZoneInfo

from .model import BgPoint, Dataset, Tick

# Ticks whose times are this close and that carry the same predictions are one loop run.
SAME_TICK_MS = 2000

# Glucose readings closer than this are copies of one reading (a CGM never sends two within 30 s).
SAME_BG_MS = 30 * 1000

# Tick.source ends with ":suggested" or ":enacted", so the merge can prefer the suggested copy.
_SUGGESTED = ":suggested"

# Series names AAPS uses in predBGs, in the order we prefer for "glucose at the tick".
_SERIES_FOR_BG = ("IOB", "UAM", "COB", "ZT", "aCOB")

_ISO_LINE = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)\s")
_ANDROID_LINE = re.compile(r"^(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+\[")
_DATE_IN_NAME = re.compile(r"(\d{4}-\d{2}-\d{2})")

# Start of a loop result object. Group 1 is the field name.
_RESULT_START = re.compile(r'(?:(?<![A-Za-z_"])(suggested|enacted|iob)=|"(suggested|enacted|iob)"\s*:\s*)\{')

_DB_GLUCOSE = re.compile(
    r"GlucoseValue\(id=\d+(?P<body>.*?), timestamp=(?P<ts>\d+),.*?, value=(?P<value>-?[\d.]+)"
)
_DB_VALID = re.compile(r"isValid=(true|false)")
_MEM_GLUCOSE = re.compile(
    r"InMemoryGlucoseValue\(timestamp=(?P<ts>\d+), value=(?P<value>-?[\d.]+)(?P<rest>[^)]*)\)"
)

_DECODER = json.JSONDecoder()


# --------------------------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------------------------


def parse_time_ms(value) -> Optional[int]:
    """Turn a Nightscout or AAPS time into epoch ms (UTC).

    Accepts epoch ms or epoch seconds (int, float or digit string) and ISO 8601 strings such as
    ``2026-01-02T03:04:05Z``, ``2026-01-02T03:04:05.123+0100`` or ``2026-01-02T03:04:05.123Z``.
    An ISO string without a zone is read as UTC. Returns None when the value cannot be read.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
        if number <= 0:
            return None
        # Below 1e11 the number is in seconds (1e11 s is in the year 5138).
        return int(round(number * 1000)) if number < 1e11 else int(round(number))
    text = str(value).strip()
    if not text:
        return None
    if re.fullmatch(r"\d+(\.\d+)?", text):
        return parse_time_ms(float(text))
    text = text.replace(" ", "T", 1) if "T" not in text and " " in text else text
    if text.endswith("Z") or text.endswith("z"):
        text = text[:-1] + "+00:00"
    # Python 3.9 fromisoformat wants "+01:00", not "+0100", and at most 6 fraction digits.
    text = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", text)
    text = re.sub(r"(\.\d{6})\d+", r"\1", text)
    match = re.match(r"^(.*T\d{2}:\d{2}:\d{2})\.(\d{1,5})((?:[+-]\d{2}:\d{2})?)$", text)
    if match:
        text = match.group(1) + "." + match.group(2).ljust(6, "0") + match.group(3)
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(round(parsed.timestamp() * 1000))


def _number(value) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number:  # NaN
        return None
    return number


def _clean_series(raw) -> Dict[str, List[float]]:
    """Keep only the predBGs arrays that are lists of numbers."""
    series: Dict[str, List[float]] = {}
    if not isinstance(raw, dict):
        return series
    for name, values in raw.items():
        if not isinstance(values, list) or not values:
            continue
        numbers = [_number(v) for v in values]
        if any(n is None for n in numbers):
            continue
        series[str(name)] = [float(n) for n in numbers]  # type: ignore[arg-type]
    return series


def _bg_from_series(series: Dict[str, List[float]]) -> Optional[float]:
    for name in _SERIES_FOR_BG:
        if name in series:
            return series[name][0]
    for values in series.values():
        return values[0]
    return None


def _result_time_ms(result: dict) -> Optional[int]:
    for key in ("timestamp", "deliverAt", "date", "mills", "created_at"):
        found = parse_time_ms(result.get(key))
        if found is not None:
            return found
    return None


def _tick_from_result(result: dict, fallback_time_ms: Optional[int], iob_object, source: str,
                      notes_counter: Dict[str, int]) -> Optional[Tick]:
    """Build a Tick from one ``suggested`` / ``enacted`` object, or None if it has no predBGs."""
    series = _clean_series(result.get("predBGs"))
    if not series:
        return None
    time_ms = _result_time_ms(result)
    if time_ms is None:
        time_ms = fallback_time_ms
        if time_ms is None:
            notes_counter["no_time"] = notes_counter.get("no_time", 0) + 1
            return None
        notes_counter["line_time"] = notes_counter.get("line_time", 0) + 1
    bg = _number(result.get("bg"))
    if bg is None:
        bg = _bg_from_series(series)
        notes_counter["bg_from_pred"] = notes_counter.get("bg_from_pred", 0) + 1
    if bg is None:
        return None
    iob_u = _number(result.get("IOB"))
    if iob_u is None:
        iob_u = _iob_number(iob_object)
    return Tick(
        time_ms=int(time_ms),
        bg=float(bg),
        series=series,
        iob_u=iob_u,
        eventual_bg=_number(result.get("eventualBG")),
        source=source,
    )


def _iob_number(iob_object) -> Optional[float]:
    """Read the total IOB from an ``openaps.iob`` value (an object, or a list of them)."""
    if isinstance(iob_object, list):
        iob_object = iob_object[0] if iob_object else None
    if isinstance(iob_object, dict):
        return _number(iob_object.get("iob"))
    return _number(iob_object)


def _merge_ticks(ticks: Iterable[Tick]) -> Tuple[List[Tick], int]:
    """Sort ticks and merge the copies of one loop run.

    Two ticks are the same run when their time is less than ``SAME_TICK_MS`` after the first
    tick of the group. This covers the same result logged several times, and the ``enacted``
    copy, which AAPS stamps a few hundred ms before or after the ``suggested`` one. The
    ``suggested`` copy wins (its time and predictions are kept); if the group has none, the
    earliest copy wins. Empty fields (IOB, eventual BG, missing series) are filled from the
    other copies. A real loop run never follows another one within 2 seconds.

    Returns the merged ticks and the number of groups where the copies have different predBGs
    (expected to be 0; the caller writes it in the notes so a change shows up).
    """
    ordered = sorted(ticks, key=lambda t: t.time_ms)
    groups: List[List[Tick]] = []
    for tick in ordered:
        if groups and tick.time_ms - groups[-1][0].time_ms < SAME_TICK_MS:
            groups[-1].append(tick)
        else:
            groups.append([tick])
    merged: List[Tick] = []
    differing = 0
    for group in groups:
        base = next((t for t in group if t.source.endswith(_SUGGESTED)), group[0])
        different = False
        for other in group:
            if other is base:
                continue
            if base.iob_u is None and other.iob_u is not None:
                base.iob_u = other.iob_u
            if base.eventual_bg is None and other.eventual_bg is not None:
                base.eventual_bg = other.eventual_bg
            for name, values in other.series.items():
                if name in base.series and base.series[name] != values:
                    different = True
                base.series.setdefault(name, values)
        if different:
            differing += 1
        merged.append(base)
    return merged, differing


def _merge_bg(points: Iterable[BgPoint]) -> Tuple[List[BgPoint], int, int]:
    """Sort glucose points and drop copies of one reading.

    A point less than ``SAME_BG_MS`` after the last kept point is a copy and is dropped (the
    earlier point is kept). Returns (kept points, number dropped, number dropped whose value
    differs from the kept one by more than 0.01 mg/dL).
    """
    ordered = sorted(points, key=lambda p: p.time_ms)
    kept: List[BgPoint] = []
    dropped = 0
    differing = 0
    for point in ordered:
        if kept and point.time_ms - kept[-1].time_ms < SAME_BG_MS:
            dropped += 1
            if abs(point.mgdl - kept[-1].mgdl) > 0.01:
                differing += 1
            continue
        kept.append(point)
    return kept, dropped, differing


def _nearest_point(points: List[BgPoint], times: List[int], time_ms: int) -> Optional[BgPoint]:
    """The point of ``points`` (sorted, times in ``times``) nearest to ``time_ms``, or None."""
    i = bisect.bisect_left(times, time_ms)
    best: Optional[BgPoint] = None
    for j in (i - 1, i):
        if 0 <= j < len(points):
            if best is None or abs(points[j].time_ms - time_ms) < abs(best.time_ms - time_ms):
                best = points[j]
    return best


# --------------------------------------------------------------------------------------------
# AAPS logs
# --------------------------------------------------------------------------------------------


def _open_log_texts(path: str) -> Iterator[Tuple[str, io.TextIOBase]]:
    """Yield (name, text stream) for a log file, or for each log inside a zip file."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for member in sorted(archive.namelist()):
                if member.endswith("/"):
                    continue
                with archive.open(member) as raw:
                    yield member, io.TextIOWrapper(raw, encoding="utf-8", errors="replace")
        return
    with open(path, "r", encoding="utf-8", errors="replace") as stream:
        yield os.path.basename(path), stream


class _LineClock:
    """Gives each log line an epoch ms time, for both the iOS and the Android line format."""

    def __init__(self, zone: ZoneInfo, day: Optional[date]):
        self.zone = zone
        self.day = day
        self.last_android_seconds: Optional[float] = None
        self.last_ms: Optional[int] = None

    def read(self, line: str) -> Optional[int]:
        """Return the time of this line, or the time of the entry above if the line has none."""
        iso = _ISO_LINE.match(line)
        if iso:
            found = parse_time_ms(iso.group(1))
            if found is not None:
                self.last_ms = found
                if self.day is None:
                    self.day = datetime.fromtimestamp(found / 1000, tz=self.zone).date()
            return self.last_ms
        android = _ANDROID_LINE.match(line)
        if android and self.day is not None:
            hours, minutes, seconds, millis = (int(g) for g in android.groups())
            of_day = hours * 3600 + minutes * 60 + seconds + millis / 1000.0
            if self.last_android_seconds is not None and of_day < self.last_android_seconds - 12 * 3600:
                self.day = self.day + timedelta(days=1)
            self.last_android_seconds = of_day
            local = datetime.combine(self.day, time(hours, minutes, seconds, millis * 1000), tzinfo=self.zone)
            self.last_ms = int(round(local.timestamp() * 1000))
        return self.last_ms


def _decode_results(line: str) -> List[Tuple[str, object]]:
    """Find every ``suggested`` / ``enacted`` / ``iob`` JSON object in one line, in order."""
    found: List[Tuple[str, object]] = []
    position = 0
    while True:
        match = _RESULT_START.search(line, position)
        if match is None:
            return found
        name = match.group(1) or match.group(2)
        start = match.end() - 1
        try:
            value, end = _DECODER.raw_decode(line, start)
        except ValueError:
            # Cut or broken JSON: skip this marker and go on.
            position = match.end()
            continue
        found.append((name, value))
        position = end


def _ticks_from_line(line: str, line_time_ms: Optional[int], source: str,
                     counter: Dict[str, int]) -> List[Tick]:
    """Build the ticks of one log line.

    In a status, ``suggested`` and ``enacted`` come first and ``iob`` comes after them, so an
    ``iob`` object belongs to the results just before it that have no IOB yet.
    """
    ticks: List[Tick] = []
    waiting: List[Tuple[str, dict]] = []
    pairs: List[Tuple[str, dict, object]] = []
    for name, value in _decode_results(line):
        if name == "iob":
            for kind, result in waiting:
                pairs.append((kind, result, value))
            waiting = []
        elif isinstance(value, dict):
            waiting.append((name, value))
    for kind, result in waiting:
        pairs.append((kind, result, None))
    for kind, result, iob_object in pairs:
        tick = _tick_from_result(result, line_time_ms, iob_object, source + ":" + kind, counter)
        if tick is not None:
            ticks.append(tick)
    return ticks


def _bg_from_line(line: str, database: List[BgPoint], memory: List[BgPoint]) -> None:
    if "GlucoseValue(id=" in line:
        for match in _DB_GLUCOSE.finditer(line):
            valid = _DB_VALID.search(match.group("body"))
            if valid and valid.group(1) == "false":
                continue
            value = _number(match.group("value"))
            if value is not None and value > 0:
                database.append(BgPoint(int(match.group("ts")), value))
    if "InMemoryGlucoseValue(" in line:
        for match in _MEM_GLUCOSE.finditer(line):
            if "filledGap=true" in match.group("rest"):
                continue
            value = _number(match.group("value"))
            if value is not None and value > 0:
                memory.append(BgPoint(int(match.group("ts")), value))


def load_aaps_logs(paths: List[str], tz_name: str) -> Dataset:
    """Read AAPS log files (iOS or Android, text or zip) into a Dataset.

    ``tz_name`` is the phone's time zone (for example ``Europe/Paris``). It is used only to read
    Android line times, which have no zone, and is stored in the Dataset for reports.
    """
    zone = ZoneInfo(tz_name)
    raw_ticks: List[Tick] = []
    database_bg: List[BgPoint] = []
    memory_bg: List[BgPoint] = []
    counter: Dict[str, int] = {}
    lines_read = 0

    for path in paths:
        for name, stream in _open_log_texts(path):
            name_date = _DATE_IN_NAME.search(name) or _DATE_IN_NAME.search(os.path.basename(path))
            day = date.fromisoformat(name_date.group(1)) if name_date else None
            clock = _LineClock(zone, day)
            source = "log:" + os.path.basename(name)
            for line in stream:
                lines_read += 1
                line_time_ms = clock.read(line)
                if "predBGs" in line:
                    raw_ticks.extend(_ticks_from_line(line, line_time_ms, source, counter))
                if "GlucoseValue(" in line:
                    _bg_from_line(line, database_bg, memory_bg)

    ticks, differing_ticks = _merge_ticks(raw_ticks)
    notes: List[str] = [
        "Source: AAPS logs, {} file(s), {} lines.".format(len(paths), lines_read),
        "Ticks: {} loop results with predBGs found, {} after merging copies of the same run "
        "(the 'suggested' copy is kept). Runs whose copies have different predBGs: {}.".format(
            len(raw_ticks), len(ticks), differing_ticks),
    ]
    if counter.get("bg_from_pred"):
        notes.append("Tick glucose: the results have no 'bg' field, so the tick glucose is predBGs[0] "
                     "(IOB series first).")
    if counter.get("line_time"):
        notes.append("{} result(s) had no timestamp; the log line time was used.".format(counter["line_time"]))
    if counter.get("no_time"):
        notes.append("{} result(s) had no time at all and were skipped.".format(counter["no_time"]))

    database_bg, db_dropped, db_differing = _merge_bg(database_bg)
    memory_bg, _, _ = _merge_bg(memory_bg)
    if database_bg or memory_bg:
        db_times = [p.time_ms for p in database_bg]
        extra: List[BgPoint] = []
        copies = 0
        mismatched = 0
        for point in memory_bg:
            nearest = _nearest_point(database_bg, db_times, point.time_ms)
            if nearest is not None and abs(nearest.time_ms - point.time_ms) < SAME_BG_MS:
                copies += 1
                if abs(nearest.mgdl - point.mgdl) > 0.01:
                    mismatched += 1
            else:
                extra.append(point)
        bg, _, _ = _merge_bg(database_bg + extra)
        notes.append(
            "Measured glucose: {} stored GlucoseValue records, plus {} readings from AUTOSENS "
            "InMemoryGlucoseValue lines (filledGap=false) with no stored value within {} s. "
            "{} AUTOSENS copies of a stored reading were dropped.".format(
                len(database_bg), len(extra), SAME_BG_MS // 1000, copies))
        if db_dropped:
            notes.append("{} stored records were copies of another stored record (same time or less "
                         "than {} s apart, for example the same reading logged again) and were dropped "
                         "({} with a different value).".format(db_dropped, SAME_BG_MS // 1000, db_differing))
        if mismatched:
            notes.append("Warning: {} AUTOSENS readings differ from the stored value of the same reading "
                         "(less than {} s apart); the stored value was kept.".format(
                             mismatched, SAME_BG_MS // 1000))
    else:
        bg, _, _ = _merge_bg(BgPoint(t.time_ms, t.bg) for t in ticks)
        notes.append("Measured glucose: no glucose records in the logs, so the tick glucose (predBGs[0]) "
                     "is used as ground truth. It is only as dense as the loop runs.")

    return Dataset(ticks=ticks, bg=bg, tz_name=tz_name, notes=notes)


# --------------------------------------------------------------------------------------------
# Nightscout exports
# --------------------------------------------------------------------------------------------


def _read_json_list(path: str) -> List[dict]:
    """Read a JSON array of documents. Also accepts one document per line (JSON lines)."""
    with open(path, "r", encoding="utf-8") as stream:
        text = stream.read()
    stripped = text.strip()
    if not stripped:
        return []
    try:
        data = json.loads(stripped)
    except ValueError:
        data = [json.loads(line) for line in stripped.splitlines() if line.strip()]
    if isinstance(data, dict):
        data = [data]
    return [doc for doc in data if isinstance(doc, dict)]


def _doc_time_ms(doc: dict) -> Optional[int]:
    for key in ("created_at", "mills", "date", "srvCreated"):
        found = parse_time_ms(doc.get(key))
        if found is not None:
            return found
    return None


def load_ns_export(devicestatus_json_path: str, entries_json_path: str, tz_name: str) -> Dataset:
    """Read Nightscout API v1 exports into a Dataset.

    ``devicestatus_json_path``: array of devicestatus documents. A tick comes from
    ``openaps.suggested``, else ``openaps.enacted``, when it has ``predBGs``. Its time is the
    result ``timestamp`` (else ``deliverAt``, else the document ``created_at`` / ``mills``). IOB is
    the result ``IOB``, else ``openaps.iob.iob``. Documents without predBGs are skipped.

    ``entries_json_path``: array of entries documents. Documents with a ``sgv`` value and a
    ``date`` in ms are used as measured glucose; other types (mbg, cal) are skipped.

    ``tz_name`` is only stored in the Dataset; all Nightscout times are absolute.
    """
    ZoneInfo(tz_name)  # fail early on a bad zone name
    counter: Dict[str, int] = {}
    raw_ticks: List[Tick] = []
    skipped = 0
    docs = _read_json_list(devicestatus_json_path)
    for doc in docs:
        openaps = doc.get("openaps")
        if not isinstance(openaps, dict):
            skipped += 1
            continue
        doc_time = _doc_time_ms(doc)
        tick = None
        for key in ("suggested", "enacted"):
            result = openaps.get(key)
            if isinstance(result, dict):
                tick = _tick_from_result(result, doc_time, openaps.get("iob"), "ns:devicestatus:" + key, counter)
                if tick is not None:
                    break
        if tick is None:
            skipped += 1
        else:
            raw_ticks.append(tick)
    ticks, differing_ticks = _merge_ticks(raw_ticks)

    points: List[BgPoint] = []
    bad_entries = 0
    for entry in _read_json_list(entries_json_path):
        entry_type = entry.get("type")
        if entry_type not in (None, "sgv"):
            continue
        value = _number(entry.get("sgv"))
        time_ms = parse_time_ms(entry.get("date"))
        if time_ms is None:
            time_ms = parse_time_ms(entry.get("dateString"))
        if value is None or value <= 0 or time_ms is None:
            bad_entries += 1
            continue
        points.append(BgPoint(time_ms, value))
    bg, bg_dropped, bg_differing = _merge_bg(points)

    notes = [
        "Source: Nightscout export, {} devicestatus documents, {} skipped (no openaps predBGs).".format(
            len(docs), skipped),
        "Ticks: {} with predBGs, {} after merging copies of the same run. Runs whose copies have "
        "different predBGs: {}.".format(len(raw_ticks), len(ticks), differing_ticks),
        "Measured glucose: {} sgv entries ({} unreadable entries skipped, {} copies at the same time "
        "or less than {} s after another entry dropped, {} of them with a different value).".format(
            len(bg), bad_entries, bg_dropped, SAME_BG_MS // 1000, bg_differing),
    ]
    if counter.get("bg_from_pred"):
        notes.append("{} result(s) had no 'bg' field; predBGs[0] was used as the tick glucose.".format(
            counter["bg_from_pred"]))
    if counter.get("line_time"):
        notes.append("{} result(s) had no timestamp; the document created_at was used.".format(counter["line_time"]))
    if not bg:
        bg, _, _ = _merge_bg(BgPoint(t.time_ms, t.bg) for t in ticks)
        notes.append("No sgv entries: the tick glucose is used as ground truth.")
    return Dataset(ticks=ticks, bg=bg, tz_name=tz_name, notes=notes)
