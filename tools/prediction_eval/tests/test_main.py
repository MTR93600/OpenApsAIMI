"""Tests for the command line (__main__.py). All data here is made up."""

import contextlib
import io
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone

from tools.prediction_eval.__main__ import main, parse_horizons, series_copy_notes
from tools.prediction_eval.model import Dataset, Tick
from tools.prediction_eval.report import REPORT_NAME, ROWS_NAME

# 2030-01-01T00:00:00Z
T0 = 1893456000000
MIN = 60 * 1000


def _iso(time_ms):
    return datetime.fromtimestamp(time_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _write_ns_files(folder):
    """Two hours of a made-up slow fall, a tick every 5 minutes."""
    statuses, entries = [], []
    for k in range(25):
        t = T0 + k * 5 * MIN
        bg = 180 - 2 * k
        entries.append({"_id": "e%d" % k, "type": "sgv", "sgv": bg, "date": t, "dateString": _iso(t)})
        iob_curve = [bg - 3 * i for i in range(25)]
        uam_curve = [bg + i for i in range(25)]
        statuses.append({
            "_id": "d%d" % k,
            "created_at": _iso(t),
            "openaps": {
                "suggested": {"timestamp": _iso(t), "bg": bg, "IOB": 1.0, "eventualBG": bg - 20,
                              "predBGs": {"IOB": iob_curve, "UAM": uam_curve, "COB": iob_curve,
                                          "ZT": iob_curve}},
                "iob": {"iob": 1.0},
            },
        })
    ds_path = os.path.join(folder, "devicestatus.json")
    en_path = os.path.join(folder, "entries.json")
    with open(ds_path, "w", encoding="utf-8") as f:
        json.dump(statuses, f)
    with open(en_path, "w", encoding="utf-8") as f:
        json.dump(entries, f)
    return ds_path, en_path


class ParseHorizonsTest(unittest.TestCase):
    def test_reads_sorts_and_removes_doubles(self):
        self.assertEqual(parse_horizons("60, 15,30,15"), [15, 30, 60])

    def test_rejects_bad_values(self):
        for text in ("7", "0", "-5", "", "a"):
            with self.assertRaises(ValueError):
                parse_horizons(text)


class SeriesCopyNotesTest(unittest.TestCase):
    def test_counts_exact_copies(self):
        same = Tick(time_ms=T0, bg=100, series={"IOB": [100, 99], "COB": [100, 99], "ZT": [100, 98]})
        other = Tick(time_ms=T0 + 5 * MIN, bg=100, series={"IOB": [100, 99], "COB": [100, 97]})
        notes = series_copy_notes(Dataset(ticks=[same, other]))
        self.assertIn("COB is an exact copy of IOB on 1 of 2 ticks", notes[0])
        self.assertIn("ZT is an exact copy of IOB on 0 of 1 ticks", notes[1])


class MainTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)

    def _run(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = main(argv)
            except SystemExit as stop:
                code = stop.code
        return code, out.getvalue(), err.getvalue()

    def test_ns_export_end_to_end(self):
        ds_path, en_path = _write_ns_files(self.folder.name)
        out_dir = os.path.join(self.folder.name, "out")
        code, out, _ = self._run(["--ns-devicestatus", ds_path, "--ns-entries", en_path,
                                  "--tz", "Europe/Paris", "--out", out_dir, "--horizons", "15,30,60"])
        self.assertEqual(code, 0)
        self.assertIn("Ticks: 25", out)
        with open(os.path.join(out_dir, REPORT_NAME), encoding="utf-8") as f:
            report = f.read()
        self.assertIn("### In 60 min", report)
        self.assertNotIn("### In 90 min", report)
        self.assertIn("COB is an exact copy of IOB on 25 of 25 ticks", report)
        self.assertTrue(os.path.exists(os.path.join(out_dir, ROWS_NAME)))
        # On a steady fall of 2 per 5 min the flat line is 24 off at 60 min. The first 3 ticks have
        # no 15-min trend, so the damped trend has no answer there and they are dropped for all.
        self.assertRegex(report, r"\| flat \| 10 \| 24\.0 \| 24\.0 \|")
        self.assertIn("| 60 | 13 | 13 | 10 | 3 |", report)
        # The made-up data is 01:00-03:00 Paris time: it must show up in a night section.
        night = report.index("## Ranking by MAE, night (00:00-05:59 local)")
        after_night = report[night:report.index("## Ranking by MAE, evening")]
        self.assertIn("### In 60 min", after_night)
        self.assertIn("## Ranking by MAE, day (06:00-21:59 local)", report)

    def test_refuses_output_inside_repository(self):
        ds_path, en_path = _write_ns_files(self.folder.name)
        repo_out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report_here")
        code, _, err = self._run(["--ns-devicestatus", ds_path, "--ns-entries", en_path, "--out", repo_out])
        self.assertEqual(code, 2)
        self.assertIn("inside the repository", err)
        self.assertFalse(os.path.exists(repo_out))

    def test_needs_exactly_one_source(self):
        code, _, _ = self._run(["--out", self.folder.name])
        self.assertEqual(code, 2)
        code, _, _ = self._run(["--aaps-log", "x.log", "--ns-entries", "y.json", "--out", self.folder.name])
        self.assertEqual(code, 2)
        code, _, _ = self._run(["--ns-entries", "y.json", "--out", self.folder.name])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
