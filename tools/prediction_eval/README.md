# prediction_eval

A small tool that measures which glucose prediction line is closest to what really happened.
It is for **contributors**. It is not a user feature, and it is not medical advice.

Python 3.9 or later, standard library only. No install step.

## What it measures

At every loop run, AAPS publishes prediction curves (`predBGs`): one value now, then one value
every 5 minutes. The home screen shows one of them. In AIMI:

- `IOB` holds the insulin-only PK/PD curve ("if nothing more is absorbed").
- `UAM` holds `scenarioBest`, a curve made for dosing. It is the line the home screen shows today.
- `COB` and `ZT` are copies of `IOB`. The report checks this on your data and says how often it is true.

The tool takes each loop run, asks several candidate predictors "what will glucose be in
15, 30, 45, 60, 90, 120 minutes?", and compares each answer with the glucose that was really
measured at that time (nearest reading within 2.5 minutes). A predictor only sees what was known
at the loop run, never later glucose. Copies of one reading (the stored value and the AUTOSENS
copy in a log, or two Nightscout entries, less than 30 s apart) count once.

Candidates (see `predictors.py`):

| Name | What it is |
|---|---|
| `flat` | Glucose stays where it is now. The baseline to beat. |
| `insulin_only` | The `IOB` curve. |
| `scenario_best` | The `UAM` curve, as shown today. |
| `mean_iob_uam` | Half way between the two. |
| `damped_trend_rX` | The trend of the last 15 minutes goes on, and slows down by factor X every 5 minutes. |
| `uam_fades_to_iob_N` | Starts on `UAM` and moves in a straight line to `IOB` over N minutes. |
| `decaying_uam` | `UAM` whose extra rise fades: fast (30 min) when glucose is falling now, slow (90 min) otherwise. |
| `band_iob_uam` | A band from `IOB` to `UAM`. |
| `band_iob_decaying_uam` | A band from `IOB` to `decaying_uam`, with the damped trend as centre. |

## Why

The line on the home screen can look flat while glucose is clearly falling, because the curve in
that slot is made for dosing, not for display. Before changing what the screen shows, we want
numbers: which line, or which band, is closest to reality, on falls, on rises and at night.

**This only concerns the display.** The loop curves are conditional scenarios: they say what
would happen if the loop did nothing more, and then the loop acts on them. A curve that loses
here can still be the right curve for dosing. Nothing in this tool changes dosing.

## How to run

Run from the repository root. Write the output to a folder **outside** the repository; the tool
refuses a folder inside it.

### On AAPS logs (iOS or Android, text or zip)

```
python3 -m tools.prediction_eval \
    --aaps-log /path/to/aaps.log [/path/to/more.zip ...] \
    --tz Europe/Paris \
    --out /some/private/folder \
    --horizons 15,30,45,60,90,120
```

`--tz` is the phone's time zone. It is needed for Android logs (their lines have no date and no
zone) and for the night/day split in the report.

### On a Nightscout export

First download the data. The site address and the token come only from environment variables,
so they never end up in your shell history as arguments or in any file:

```
export NS_URL=https://your-site.example
export NS_TOKEN=your-read-token
python3 -m tools.prediction_eval.fetch_ns \
    --start 2026-01-01 --end 2026-01-29 --tz Europe/Paris \
    --out-dir /some/private/folder/ns
```

`--end` is not included. Then score it:

```
python3 -m tools.prediction_eval \
    --ns-devicestatus /some/private/folder/ns/devicestatus.json \
    --ns-entries /some/private/folder/ns/entries.json \
    --tz Europe/Paris --out /some/private/folder/report
```

Use a token with read access only.

### Tests

```
python3 -m unittest discover -s tools/prediction_eval/tests -t .
```

The tests use made-up data only.

## Privacy

Logs, Nightscout exports, the report (`prediction_eval_report.md`) and the CSV
(`prediction_eval_rows.csv`) all hold **health data**. Keep them out of git, out of issues and
pull requests, and out of chat logs you share. Do not paste real values into tests or comments.
If you share results, share the summary numbers, not the rows.

## How to read the report

- **MAE**: mean absolute error in mg/dL. Lower is better.
- **Bias**: mean of (predicted - real). Below 0 means the line is too low on average.
- **|error| > 40**: share of predictions that were off by more than 40 mg/dL.
- **Same cases**: some predictors give no answer on some ticks (the damped trend needs the last
  15 minutes of glucose). All tables use only the ticks where every predictor answered, so the
  predictors are compared on the same cases. The report says how many ticks were dropped.
- **Rankings** are given for all data, then only when glucose was falling, then only when it was
  rising (trend of the last 15 minutes, more than 2 mg/dL per 5 minutes), then by local time of
  the tick: night (00:00-05:59), evening (22:00-23:59) and day (06:00-21:59), then with IOB of
  3 U or more. Night follows the usual CGM definition; the evening is kept apart because it often
  holds the end of dinner.
- **Bands**: coverage is how often the real glucose was inside the band. A useful band covers
  about 80 % of the time and is as narrow as possible.
- **Calibrated band**: the first 70 % of the time learns how wide a band around each centre line
  must be; the last 30 % checks it. Only the last part is reported. In the first part, a
  prediction is used only when its real glucose was measured before the last part starts, so no
  glucose of the test period is used to learn the width.
- **Low glucose detection**: how often a line warned (value under 80) when real glucose went
  under 70, and how often it warned when real glucose stayed at 90 or more.

Some candidates are equal at some horizons by design. For example `uam_fades_to_iob_60` is
`insulin_only` at 60 minutes and later, and `mean_iob_uam` is the centre of `band_iob_uam`.

One day is not enough to decide. Use several weeks and look at each group (night, falling,
rising) before choosing what to display.
