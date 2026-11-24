"""Download Nightscout devicestatus and entries for a date range (API v1).

Usage (from the repository root):

    NS_URL=https://my-site.example NS_TOKEN=... \\
        python3 -m tools.prediction_eval.fetch_ns --start 2026-01-01 --end 2026-01-15 \\
        --tz Europe/Paris --out-dir /some/private/folder

It writes ``devicestatus.json`` and ``entries.json`` (JSON arrays) into ``--out-dir``. Read them
with ``loaders.load_ns_export``.

Rules:

* The site URL and the token come ONLY from the environment variables ``NS_URL`` and
  ``NS_TOKEN``. The token is sent as the ``token`` query parameter. It is never printed or
  written to a file, and error messages never show the request URL.
* The files hold health data. ``--out-dir`` must be outside the git repository.
* ``--start`` and ``--end`` are local dates (or ISO date-times) in ``--tz``. ``--end`` is not
  included: ``--start 2026-01-01 --end 2026-01-02`` downloads one day.

Paging: Nightscout returns the newest documents first, at most ``count`` per call. We ask for
``find[<field>][$gte]=start`` and ``find[<field>][$lt]=end``, then move the upper bound to the
oldest time seen (now with ``$lte``) and ask again, until a call returns fewer than ``count``
documents. Documents are de-duplicated by ``_id``. devicestatus is filtered on ``created_at``
(an ISO string, compared as text by the server, so we send the same ``...T..:..:..sssZ`` form
AAPS writes); entries are filtered on ``date`` (epoch ms).

Python 3.9, standard library only.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, time, timezone
from typing import Callable, Dict, List, Optional, Tuple

from zoneinfo import ZoneInfo

PAGE_SIZE = 1000
MAX_PAGES = 1000
TIMEOUT_S = 60


def parse_local_time(text: str, zone: ZoneInfo) -> datetime:
    """Read ``YYYY-MM-DD`` (midnight) or an ISO date-time; no zone means local time in ``zone``."""
    if len(text) == 10:
        parsed = datetime.combine(datetime.strptime(text, "%Y-%m-%d").date(), time(0, 0))
    else:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=zone)
    return parsed.astimezone(timezone.utc)


def iso_utc(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + \
        "{:03d}Z".format(moment.microsecond // 1000)


def build_url(base_url: str, collection: str, field: str, low: str, high: str, token: str,
              count: int, high_included: bool = False) -> str:
    """Build an API v1 query URL. Contains the token: never print or log the result."""
    query = [
        ("find[{}][$gte]".format(field), low),
        ("find[{}][{}]".format(field, "$lte" if high_included else "$lt"), high),
        ("count", str(count)),
    ]
    if token:
        query.append(("token", token))
    return "{}/api/v1/{}.json?{}".format(base_url.rstrip("/"), collection, urllib.parse.urlencode(query))


def http_get_json(url: str) -> object:
    """GET a URL and decode the JSON body. Errors do not show the URL (it holds the token)."""
    request = urllib.request.Request(url, headers={"Accept": "application/json",
                                                   "User-Agent": "prediction-eval/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise RuntimeError("Nightscout answered HTTP {} {}".format(error.code, error.reason)) from None
    except urllib.error.URLError as error:
        raise RuntimeError("Cannot reach Nightscout: {}".format(error.reason)) from None


def fetch_range(get_page: Callable[[str, str, bool], List[dict]], low: str, high: str,
                time_of: Callable[[dict], Optional[str]], page_size: int = PAGE_SIZE) -> List[dict]:
    """Page backward through ``[low, high)`` and return all documents, oldest first.

    ``get_page(low, upper, upper_included)`` returns one page (newest first). ``time_of(doc)``
    gives the value to use as the next ``upper`` bound, in the same form as ``low`` / ``high``.
    The first call excludes ``high``. Later calls include the new bound, because a page can end
    in the middle of documents that share the oldest time; the copies are removed by ``_id``.
    """
    seen: Dict[str, dict] = {}
    upper = high
    upper_included = False
    for _ in range(MAX_PAGES):
        page = get_page(low, upper, upper_included)
        if not isinstance(page, list):
            raise RuntimeError("Nightscout did not return a list")
        new_docs = 0
        oldest: Optional[str] = None
        for doc in page:
            if not isinstance(doc, dict):
                continue
            key = str(doc.get("_id") or json.dumps(doc, sort_keys=True))
            if key not in seen:
                seen[key] = doc
                new_docs += 1
            stamp = time_of(doc)
            if stamp is not None and (oldest is None or _before(stamp, oldest)):
                oldest = stamp
        if len(page) < page_size or oldest is None:
            break
        if new_docs == 0:
            # A full page with nothing new: the bound cannot move, documents would be lost.
            raise RuntimeError("More than {} documents share one time; use a larger --page-size".format(page_size))
        upper = oldest
        upper_included = True
    else:
        raise RuntimeError("Too many pages; use a shorter date range")
    docs = list(seen.values())
    docs.sort(key=lambda d: _sort_key(time_of(d)))
    return docs


def _before(a: str, b: str) -> bool:
    return _sort_key(a) < _sort_key(b)


def _sort_key(value: Optional[str]) -> Tuple[int, float, str]:
    if value is None:
        return (1, 0.0, "")
    try:
        return (0, float(value), "")
    except ValueError:
        pass
    try:
        return (0, datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000, "")
    except ValueError:
        return (0, 0.0, value)


def _devicestatus_time(doc: dict) -> Optional[str]:
    value = doc.get("created_at")
    return str(value) if value else None


def _entry_time(doc: dict) -> Optional[str]:
    value = doc.get("date")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(int(value))
    return None


def _is_inside(path: str, folder: str) -> bool:
    path = os.path.realpath(path)
    folder = os.path.realpath(folder)
    return path == folder or path.startswith(folder + os.sep)


def _repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Download Nightscout devicestatus and entries (API v1).")
    parser.add_argument("--start", required=True, help="first local date, YYYY-MM-DD (or ISO date-time)")
    parser.add_argument("--end", required=True, help="end local date, not included")
    parser.add_argument("--tz", default="UTC", help="time zone of the dates, e.g. Europe/Paris")
    parser.add_argument("--out-dir", required=True, help="private folder outside the repository")
    parser.add_argument("--page-size", type=int, default=PAGE_SIZE)
    args = parser.parse_args(argv)

    base_url = os.environ.get("NS_URL", "").strip()
    token = os.environ.get("NS_TOKEN", "").strip()
    if not base_url:
        print("Set the NS_URL environment variable.", file=sys.stderr)
        return 2
    if not base_url.lower().startswith(("https://", "http://")):
        print("NS_URL must start with https:// or http://", file=sys.stderr)
        return 2
    if _is_inside(args.out_dir, _repo_root()):
        print("Refusing to write health data inside the repository. Choose another --out-dir.", file=sys.stderr)
        return 2

    zone = ZoneInfo(args.tz)
    start = parse_local_time(args.start, zone)
    end = parse_local_time(args.end, zone)
    if end <= start:
        print("--end must be after --start", file=sys.stderr)
        return 2

    def devicestatus_page(low: str, high: str, high_included: bool) -> List[dict]:
        url = build_url(base_url, "devicestatus", "created_at", low, high, token, args.page_size, high_included)
        return http_get_json(url)  # type: ignore[return-value]

    def entries_page(low: str, high: str, high_included: bool) -> List[dict]:
        url = build_url(base_url, "entries", "date", low, high, token, args.page_size, high_included)
        return http_get_json(url)  # type: ignore[return-value]

    try:
        statuses = fetch_range(devicestatus_page, iso_utc(start), iso_utc(end), _devicestatus_time,
                               args.page_size)
        start_ms = str(int(start.timestamp() * 1000))
        end_ms = str(int(end.timestamp() * 1000))
        entries = fetch_range(entries_page, start_ms, end_ms, _entry_time, args.page_size)
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1

    os.makedirs(args.out_dir, exist_ok=True)
    for name, docs in (("devicestatus.json", statuses), ("entries.json", entries)):
        with open(os.path.join(args.out_dir, name), "w", encoding="utf-8") as stream:
            json.dump(docs, stream)
    print("Wrote {} devicestatus and {} entries documents to {}".format(len(statuses), len(entries), args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
