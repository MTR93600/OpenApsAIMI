"""Tests for loaders.py and fetch_ns.py. All data here is made up."""

import json
import os
import tempfile
import unittest
import zipfile

from tools.prediction_eval import fetch_ns
from tools.prediction_eval.loaders import _merge_bg, _merge_ticks, load_aaps_logs, load_ns_export, parse_time_ms
from tools.prediction_eval.model import BgPoint, Tick

# 2030-01-01T00:00:00Z
T0 = 1893456000000
MIN = 60 * 1000


def _result(time_iso, iob_curve, uam_curve, eventual=None, extra=None):
    data = {"algorithm": "TEST", "timestamp": time_iso, "eventualBG": eventual,
            "reason": "made up, with } and { inside a string", "predBGs": {"IOB": iob_curve, "UAM": uam_curve}}
    if extra:
        data.update(extra)
    return json.dumps(data, separators=(",", ":"))


def _ios_status_line(line_time, result_json, iob=1.5, enacted_json=None):
    enacted = enacted_json if enacted_json is not None else "null"
    return ("{} DEBUG DATABASE: Inserted DeviceStatus DS(id=1, timestamp=1, device=test, "
            "pump={{\"reservoir\":10}}, enacted={}, suggested={}, iob={{\"iob\":{},\"basaliob\":0.1}}, "
            "uploaderBattery=50)\n").format(line_time, enacted, result_json, iob)


def _glucose_line(line_time, time_ms, value, valid=True):
    return ("{} DEBUG DATABASE: Inserted GlucoseValue from Test GlucoseValue(id=7, version=0, "
            "dateCreated=1, isValid={}, referenceId=null, timestamp={}, utcOffset=0, raw=null, "
            "value={}, trendArrow=FLAT, noise=null, sourceSensor=TEST)\n").format(
                line_time, "true" if valid else "false", time_ms, value)


def _autosens_line(line_time, time_ms, value, filled=False):
    return ("{} DEBUG AUTOSENS: Adding. InMemoryGlucoseValue(timestamp={}, value={}, trendArrow=NONE, "
            "smoothed=null, calibrated=null, filledGap={}, sourceSensor=TEST)\n").format(
                line_time, time_ms, value, "true" if filled else "false")


class ParseTimeTest(unittest.TestCase):
    def test_formats(self):
        self.assertEqual(parse_time_ms("2030-01-01T00:00:00Z"), T0)
        self.assertEqual(parse_time_ms("2030-01-01T00:00:00.250Z"), T0 + 250)
        self.assertEqual(parse_time_ms("2030-01-01T01:00:00.000+0100"), T0)
        self.assertEqual(parse_time_ms("2030-01-01T01:00:00+01:00"), T0)
        self.assertEqual(parse_time_ms(T0), T0)
        self.assertEqual(parse_time_ms(T0 / 1000), T0)
        self.assertEqual(parse_time_ms(str(T0)), T0)
        self.assertIsNone(parse_time_ms("not a time"))
        self.assertIsNone(parse_time_ms(None))


class AapsLogTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.folder.cleanup()

    def _write(self, name, text):
        path = os.path.join(self.folder.name, name)
        with open(path, "w", encoding="utf-8") as stream:
            stream.write(text)
        return path

    def test_ios_log_ticks_are_merged_and_sorted(self):
        first = _result("2030-01-01T00:05:00.000Z", [150, 140, 130], [150, 151, 152], eventual=120)
        second = _result("2030-01-01T00:00:00.000Z", [160, 150], [160, 161], eventual=130)
        enacted_copy = _result("2030-01-01T00:05:00.300Z", [150, 140, 130], [150, 151, 152])
        text = (
            _ios_status_line("2030-01-01T00:05:01Z", first, iob=2.0, enacted_json=enacted_copy)
            + _ios_status_line("2030-01-01T00:05:01Z", first, iob=2.0)  # same run logged again
            + "a continuation line with no time\n"
            + _ios_status_line("2030-01-01T00:00:01Z", second, iob=1.0)
            + _glucose_line("2030-01-01T00:00:02Z", T0, 160)
            + _glucose_line("2030-01-01T00:05:02Z", T0 + 5 * MIN, 150)
            + _glucose_line("2030-01-01T00:05:02Z", T0 + 6 * MIN, 999, valid=False)
            + _autosens_line("2030-01-01T00:05:03Z", T0 - 5 * MIN, 170)
            + _autosens_line("2030-01-01T00:05:03Z", T0 - 10 * MIN, 175, filled=True)
            + _autosens_line("2030-01-01T00:05:03Z", T0, 160)
        )
        dataset = load_aaps_logs([self._write("aaps.log", text)], "Europe/Paris")

        self.assertEqual([t.time_ms for t in dataset.ticks], [T0, T0 + 5 * MIN])
        late = dataset.ticks[1]
        self.assertEqual(late.bg, 150)  # no "bg" field: predBGs[0] of IOB
        self.assertEqual(late.iob_u, 2.0)
        self.assertEqual(late.eventual_bg, 120)
        self.assertEqual(late.series["UAM"], [150, 151, 152])
        self.assertEqual(dataset.ticks[0].iob_u, 1.0)
        self.assertEqual([(p.time_ms, p.mgdl) for p in dataset.bg],
                         [(T0 - 5 * MIN, 170), (T0, 160), (T0 + 5 * MIN, 150)])
        self.assertEqual(dataset.tz_name, "Europe/Paris")
        self.assertTrue(any("predBGs[0]" in note for note in dataset.notes))

    def test_bg_field_and_iob_number_win(self):
        result = _result("2030-01-01T00:00:00Z", [100, 90], [100, 95], extra={"bg": 101, "IOB": 0.7})
        dataset = load_aaps_logs([self._write("aaps.log", _ios_status_line("2030-01-01T00:00:01Z", result))],
                                 "UTC")
        self.assertEqual(dataset.ticks[0].bg, 101)
        self.assertEqual(dataset.ticks[0].iob_u, 0.7)

    def test_fallback_to_tick_bg_when_no_glucose_lines(self):
        result = _result("2030-01-01T00:00:00Z", [100, 90], [100, 95])
        dataset = load_aaps_logs([self._write("aaps.log", _ios_status_line("2030-01-01T00:00:01Z", result))],
                                 "UTC")
        self.assertEqual([(p.time_ms, p.mgdl) for p in dataset.bg], [(T0, 100)])
        self.assertTrue(any("ground truth" in note for note in dataset.notes))

    def test_broken_json_is_skipped(self):
        cut = _result("2030-01-01T00:00:00Z", [100, 90], [100, 95])[:-10]
        good = _result("2030-01-01T00:05:00Z", [100, 90], [100, 95])
        text = ("2030-01-01T00:00:01Z DEBUG X: suggested=" + cut + "\n"
                + _ios_status_line("2030-01-01T00:05:01Z", good))
        dataset = load_aaps_logs([self._write("aaps.log", text)], "UTC")
        self.assertEqual([t.time_ms for t in dataset.ticks], [T0 + 5 * MIN])

    def test_android_log_in_zip_uses_name_date_and_local_time(self):
        # Result without timestamp: the line time must be used. Paris is UTC+1 in January.
        result = json.dumps({"bg": 120, "predBGs": {"IOB": [120, 110]}})
        text = (
            "23:59:58.000 [main] D/APS: [Loop.run():1]: before midnight\n"
            "00:00:30.500 [main] D/APS: [Loop.run():1]: suggested=" + result + "\n"
        )
        zip_path = os.path.join(self.folder.name, "AndroidAPS._2029-12-31_23-00-00_.0.zip")
        with zipfile.ZipFile(zip_path, "w") as archive:
            archive.writestr("AndroidAPS.log", text)
        dataset = load_aaps_logs([zip_path], "Europe/Paris")
        # 2030-01-01 00:00:30.500 Paris = 2029-12-31 23:00:30.500 UTC
        self.assertEqual(dataset.ticks[0].time_ms, T0 - 60 * MIN + 30500)
        self.assertEqual(dataset.ticks[0].bg, 120)


class GlucoseCopiesTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.folder.cleanup()

    def test_merge_bg_drops_near_copies(self):
        kept, dropped, differing = _merge_bg([BgPoint(1000000, 100), BgPoint(1001500, 100)])
        self.assertEqual(kept, [BgPoint(1000000, 100)])
        self.assertEqual((dropped, differing), (1, 0))
        kept, dropped, differing = _merge_bg([BgPoint(T0, 100), BgPoint(T0 + 20 * 1000, 104),
                                              BgPoint(T0 + MIN, 101)])
        self.assertEqual([p.time_ms for p in kept], [T0, T0 + MIN])  # 1-min CGM data stays
        self.assertEqual((dropped, differing), (1, 1))

    def test_autosens_copy_one_second_off_counts_once_and_is_checked(self):
        result = _result("2030-01-01T00:05:00Z", [150, 140], [150, 151])
        text = (
            _ios_status_line("2030-01-01T00:05:01Z", result)
            + _glucose_line("2030-01-01T00:00:02Z", T0, 160)
            + _glucose_line("2030-01-01T00:05:02Z", T0 + 5 * MIN, 150)
            + _autosens_line("2030-01-01T00:05:03Z", T0 + 1000, 160)          # same reading, 1 s off
            + _autosens_line("2030-01-01T00:05:03Z", T0 + 5 * MIN + 2000, 152)  # 2 s off, other value
            + _autosens_line("2030-01-01T00:05:03Z", T0 - 5 * MIN, 170)       # older, not stored
        )
        path = os.path.join(self.folder.name, "aaps.log")
        with open(path, "w", encoding="utf-8") as stream:
            stream.write(text)
        dataset = load_aaps_logs([path], "UTC")
        self.assertEqual([(p.time_ms, p.mgdl) for p in dataset.bg],
                         [(T0 - 5 * MIN, 170), (T0, 160), (T0 + 5 * MIN, 150)])
        notes = " ".join(dataset.notes)
        self.assertIn("plus 1 readings from AUTOSENS", notes)
        self.assertIn("2 AUTOSENS copies of a stored reading were dropped", notes)
        self.assertIn("Warning: 1 AUTOSENS readings differ", notes)


class MergeTicksTest(unittest.TestCase):
    def test_suggested_copy_wins_even_when_enacted_is_earlier(self):
        enacted = Tick(time_ms=T0, bg=100, series={"IOB": [100, 90]}, source="log:a:enacted")
        suggested = Tick(time_ms=T0 + 400, bg=100, series={"IOB": [100, 90], "UAM": [100, 95]},
                         iob_u=None, source="log:a:suggested")
        later = Tick(time_ms=T0 + 900, bg=100, series={"IOB": [100, 90]}, iob_u=1.2, source="log:a:enacted")
        merged, differing = _merge_ticks([later, suggested, enacted])
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0].time_ms, T0 + 400)
        self.assertTrue(merged[0].source.endswith(":suggested"))
        self.assertEqual(merged[0].iob_u, 1.2)
        self.assertEqual(differing, 0)

    def test_counts_runs_whose_copies_differ(self):
        a = Tick(time_ms=T0, bg=100, series={"IOB": [100, 90]}, source="log:a:suggested")
        b = Tick(time_ms=T0 + 300, bg=100, series={"IOB": [100, 80]}, source="log:a:enacted")
        c = Tick(time_ms=T0 + 5 * MIN, bg=100, series={"IOB": [100, 90]}, source="log:a:suggested")
        merged, differing = _merge_ticks([a, b, c])
        self.assertEqual(len(merged), 2)
        self.assertEqual(merged[0].series["IOB"], [100, 90])  # the suggested predictions are kept
        self.assertEqual(differing, 1)

    def test_log_note_has_the_count(self):
        enacted = _result("2030-01-01T00:00:00.000Z", [150, 130], [150, 151])
        suggested = _result("2030-01-01T00:00:00.300Z", [150, 140], [150, 151])
        line = _ios_status_line("2030-01-01T00:00:01Z", suggested, enacted_json=enacted)
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "aaps.log")
            with open(path, "w", encoding="utf-8") as stream:
                stream.write(line)
            dataset = load_aaps_logs([path], "UTC")
        self.assertEqual(len(dataset.ticks), 1)
        self.assertEqual(dataset.ticks[0].series["IOB"], [150, 140])
        self.assertEqual(dataset.ticks[0].time_ms, T0 + 300)
        self.assertTrue(any("different predBGs: 1" in note for note in dataset.notes))


class NightscoutExportTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.folder.cleanup()

    def _write(self, name, data):
        path = os.path.join(self.folder.name, name)
        with open(path, "w", encoding="utf-8") as stream:
            json.dump(data, stream)
        return path

    def test_export(self):
        statuses = [
            {"_id": "a", "created_at": "2030-01-01T00:05:02.000Z",
             "openaps": {"suggested": {"bg": 150, "timestamp": "2030-01-01T00:05:00.000Z", "eventualBG": 110,
                                       "predBGs": {"IOB": [150, 140], "ZT": [150, 130]}},
                         "iob": {"iob": 2.5}}},
            {"_id": "b", "created_at": "2030-01-01T00:05:03.000Z",  # same run uploaded twice
             "openaps": {"suggested": {"bg": 150, "timestamp": "2030-01-01T00:05:00.000Z",
                                       "predBGs": {"IOB": [150, 140]}}}},
            {"_id": "c", "created_at": "2030-01-01T00:00:02.000Z",
             "openaps": {"enacted": {"bg": 160, "deliverAt": "2030-01-01T00:00:00.000Z", "IOB": 1.0,
                                     "predBGs": {"IOB": [160, 150]}},
                         "iob": [{"iob": 9.9}]}},
            {"_id": "d", "created_at": "2030-01-01T00:02:00.000Z", "pump": {"reservoir": 10}},
            {"_id": "e", "created_at": "2030-01-01T00:03:00.000Z", "openaps": {"suggested": {"bg": 100}}},
        ]
        entries = [
            {"_id": "1", "type": "sgv", "sgv": 150, "date": T0 + 5 * MIN},
            {"_id": "2", "type": "sgv", "sgv": 160, "date": T0},
            {"_id": "3", "type": "sgv", "sgv": 160, "date": T0},
            {"_id": "5", "type": "sgv", "sgv": 161, "date": T0 + 10 * 1000},  # near copy, 10 s later
            {"_id": "4", "type": "mbg", "mbg": 155, "date": T0 + MIN},
        ]
        dataset = load_ns_export(self._write("ds.json", statuses), self._write("en.json", entries), "UTC")
        self.assertEqual([t.time_ms for t in dataset.ticks], [T0, T0 + 5 * MIN])
        self.assertEqual(dataset.ticks[0].iob_u, 1.0)
        self.assertEqual(dataset.ticks[1].iob_u, 2.5)
        self.assertEqual(dataset.ticks[1].eventual_bg, 110)
        self.assertEqual(dataset.ticks[1].series["ZT"], [150, 130])
        self.assertEqual([(p.time_ms, p.mgdl) for p in dataset.bg], [(T0, 160), (T0 + 5 * MIN, 150)])
        self.assertTrue(any("2 copies at the same time or less than 30 s after another entry dropped, 1 of them with a "
                            "different value" in note for note in dataset.notes))


class FetchNsTest(unittest.TestCase):
    def test_url_has_filters_and_token(self):
        url = fetch_ns.build_url("https://ns.example/", "entries", "date", "1", "2", "secret", 10)
        self.assertTrue(url.startswith("https://ns.example/api/v1/entries.json?"))
        self.assertIn("find%5Bdate%5D%5B%24gte%5D=1", url)
        self.assertIn("find%5Bdate%5D%5B%24lt%5D=2", url)
        self.assertIn("count=10", url)
        self.assertIn("token=secret", url)
        included = fetch_ns.build_url("https://ns.example", "entries", "date", "1", "2", "", 10, True)
        self.assertIn("%24lte", included)
        self.assertNotIn("token", included)

    def test_paging_goes_back_until_short_page(self):
        docs = [{"_id": str(i), "date": i} for i in range(25)]
        calls = []

        def get_page(low, upper, included):
            calls.append((upper, included))
            inside = [d for d in docs if d["date"] >= int(low)
                      and (d["date"] <= int(upper) if included else d["date"] < int(upper))]
            return sorted(inside, key=lambda d: -d["date"])[:10]

        found = fetch_ns.fetch_range(get_page, "0", "25", fetch_ns._entry_time, page_size=10)
        self.assertEqual([d["date"] for d in found], list(range(25)))
        self.assertEqual(calls[0], ("25", False))
        self.assertTrue(all(included for _, included in calls[1:]))

    def test_local_dates(self):
        moment = fetch_ns.parse_local_time("2030-01-01", fetch_ns.ZoneInfo("Europe/Paris"))
        self.assertEqual(int(moment.timestamp() * 1000), T0 - 60 * MIN)
        self.assertEqual(fetch_ns.iso_utc(moment), "2029-12-31T23:00:00.000Z")


if __name__ == "__main__":
    unittest.main()
