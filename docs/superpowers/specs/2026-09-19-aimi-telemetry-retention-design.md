# AIMI telemetry retention — design

Date: 2026-09-19
Status: design approved, implementation not started

## 1. Problem

The AIMI plugin writes about 13 append-only telemetry files into the AIMI storage
directory (`Documents/AAPS` when writable, app-scoped external storage otherwise).
Only one of them has any retention at all: `autodrive_dataset.csv`, pruned at 60 days
by `AutodriveDataBackfiller`. Every other file grows without any bound, for as long as
the app runs.

### Measured on the user's device, 2026-09-19

The directory holds about **2.6 GB**. One file is 80% of it.

| File | Size | Days | Rate |
| --- | --- | --- | --- |
| `AIMI_Decisions.jsonl` | **2.10 GB** | ~190 | ~11 MB/day average, 15–20 MB/day now |
| `AIMI_HORMONITOR_event_stream_v1.jsonl` | 186 MB | ~190 | ~1 MB/day |
| `AIMI_HORMONITOR_loop_blackbox_v1.jsonl` | 69.6 MB | ~190 | ~0.37 MB/day |
| `AIMI_HORMONITOR_shadow_contributions_v1.jsonl` | 56.6 MB | ~190 | ~0.30 MB/day |
| `oapsaimi2_records.csv` | 29.8 MB | ~190 | ~0.16 MB/day |
| `oapsaimi_wcycle.csv` | 26.3 MB | ~190 | ~0.14 MB/day |
| `oapsaimi_pkpd_records.csv` | 25.5 MB | ~190 | ~0.13 MB/day |
| `basal_adaptive_records.csv` | 16.9 MB | ~190 | out of scope, see 3 |
| `oapsaimiML2_records.csv` | 12.7 MB | ~190 | out of scope, see 3 |
| `autodrive_dataset.csv` | 6.64 MB | 60 (pruned) | already managed |
| `AIMI_HORMONITOR_daily_outcomes_v1.jsonl` | 4.03 MB | ~190 | ~21 KB/day |
| `AIMI_HORMONITOR_dataset_qa_v1.jsonl` | 2.28 MB | ~190 | ~12 KB/day, ~12 KB per line |
| `aimi_reactivity_analysis.csv` | 1.69 MB | ~190 | ~9 KB/day |

The decision file has one line per loop tick (about 290 per day), each holding the full
`DecisionContext.toMedicalJson()` blob: 5 root keys, about 95 fields in `baseline_state`
and about 37 nested objects in `adjustments`. That works out at roughly 38 KB per line on
average, and more now, because the schema grew in April, June and August 2026. The code
itself calls it "this multi-hundred-KB blob", which is why it was removed from the
Nightscout device status.

At the same time, no in-app consumer reads far back:

| Reader | Window actually read |
| --- | --- |
| `T3cRuntimeHistoryReader`, `HarmoniaRuntimeHistoryReader` | last 120 lines, 400 max, 24 h |
| Support package ZIP export | 24 h |
| In-app Hormonitor viewer (`HormonitorReader`) | per day, over `event_stream` |
| External viewer APK (`tools/aimi_viewer`) | 31 days |

So 2.10 GB is kept to feed a 24 hour window. The support package export is a good
illustration: `AimiProfileAdvisorActivity` streams the **whole** 2.10 GB file line by
line to pull out the last 24 hours. It does not run out of memory, but it reads 2.10 GB
every time a support package is produced.

### Dead files

Four files are no longer written by any code. They were found on the device, not in the
source, and together they hold about 69 MB:

| File | Size | Last written |
| --- | --- | --- |
| `oapsaimiHB_records.csv` | 28.3 MB | 2024-11-12 |
| `oapsaimi_records.csv` | 20.9 MB | 2025-04-01 |
| `comparison_aimi_smb.csv` | 19.5 MB | 2026-05-01 (its preference is off) |
| `bg.csv` | 662 B | 2025-02-20 |

## 2. Decisions taken

These were agreed with the user. They are inputs, not proposals.

1. **Keep the history, do not delete it.** Old data is compressed with gzip rather than
   dropped, so an incident reported several weeks later can still be investigated.
2. **`AIMI_Decisions.jsonl` keeps 7 days in clear text**, and the external viewer APK
   learns to read the `.gz` archives. Seven days is still seven times the 24 hour window
   that every in-app reader uses. This was chosen over 31 days once the real size was
   measured: 31 days would have left a permanent 400–600 MB plateau.
3. **Files that no in-app code reads keep 7 days in clear text** and are then archived as
   `.gz`. They are not switched off, because the external viewer and offline analysis
   still use some of them.
4. **Approach C (hybrid).** A periodic janitor does all the expensive work off the dosing
   path, plus a very cheap hard-cap guard in the writers, so that a run of missed janitor
   passes cannot let a file grow without limit.

### One policy decision made from the measurements

`AIMI_HORMONITOR_event_stream_v1.jsonl` keeps **31 days**, not 7. It only grows at about
1 MB/day, so 31 days costs about 31 MB, and it is the file the **in-app** Hormonitor
viewer reads. Cutting it to 7 days would save about 25 MB and would force a change in
`HormonitorReader`. Not worth it. Only `tools/aimi_viewer` needs gzip support, and only
for the decision file.

### Expected result

About **410 MB** in total, against 2.6 GB today, so roughly an 85% reduction. The
decision file goes from 2.10 GB to about 130 MB of clear text plus about 200 MB of
archive.

## 3. Scope

### In scope

A new package under
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/`:

- `AimiRetentionPolicy` — the declarative table of what is kept, per file.
- `AimiRetentionManager` — the trim/archive/evict engine.
- `AimiRetentionWorker` — the WorkManager entry point.
- `AimiFileLock` — a small generalisation of `AutodriveDatasetLock`, keyed by file path.
- `AimiAppendGuard` — the cheap write-time hard cap.

Call sites: `AuditorJsonlExport.appendLine`, the Hormonitor exporter's `writeToFile`, and
one new periodic work registration in `AimiMlTrainingScheduler`.

Plus, in the external viewer (`tools/aimi_viewer`): open `archive/*.jsonl.gz` beside the
live file, so the viewer's 31 day window keeps working now that the live file holds 7
days.

### Out of scope

- **The shape of the decision blob.** Writing ~38 KB every 5 minutes to serve a 24 hour
  window is the real cause, but changing what is logged is a separate change with its own
  forensic risk.
- **The ML training corpora** (`oapsaimiML2_records.csv` 12.7 MB,
  `basal_adaptive_records.csv` 16.9 MB). `AimiSmbTrainer` and `BasalMlDatasetParser` read
  them whole with `readLines()`, so they need a row cap for out-of-memory safety. That is
  a safety fix with different reasoning and a different risk profile, tracked separately.
  Their size is not the problem here.
- **`autodrive_dataset.csv`.** It already has retention and its own lock. Not touched.
- **The storage path inconsistency.** The writer resolves the file through
  `AimiStorageHelper`, while `T3cRuntimeHistoryReader.aimiDecisionsJsonlFile()` rebuilds
  `Documents/AAPS` by hand. If the helper falls back to app-scoped storage, the readers
  see nothing. A pre-existing defect, unrelated to retention, recorded here so it is not
  forgotten.

## 4. Retention policy

Each managed file gets one of four operations:

- **TRIM_TIME** — cut the lines older than `hot` out of the live file and append them to
  a compressed archive. Only for files that carry an epoch-milliseconds key in every
  line. This is the algorithm in section 5.
- **TRIM_LINES** — keep the last `hot` lines and archive everything before them. Same
  algorithm, with the cut offset found by line count instead of by timestamp. For files
  whose lines carry no machine-readable timestamp (see 5.1).
- **DROP** — delete the whole file once it is older than `hot`. Only for files that are
  already dead copies, never for a live telemetry stream.
- **RETIRE** — archive the whole file as `.gz`, then remove the original. For files
  nothing writes any more.

`hot` is the clear-text window kept in the live file; `archive` is how long the
compressed history is kept afterwards.

| File | Op | Hot | Key / rate | Archive | Header |
| --- | --- | --- | --- | --- | --- |
| `AIMI_Decisions.jsonl` | TRIM_TIME | 7 d | `"timestamp"` | 12 months | no |
| `AIMI_HORMONITOR_event_stream_v1.jsonl` | TRIM_TIME | 31 d | `"timestamp"` | 12 months | no |
| `AIMI_HORMONITOR_shadow_contributions_v1.jsonl` | TRIM_TIME | 7 d | `"timestamp"` | 3 months | no |
| `AIMI_HORMONITOR_loop_blackbox_v1.jsonl` | TRIM_TIME | 7 d | **`"wall_ms"`** | 3 months | no |
| `AIMI_HORMONITOR_daily_outcomes_v1.jsonl` | TRIM_LINES | 400 lines | ~1/day measured | 24 months | no |
| `AIMI_HORMONITOR_dataset_qa_v1.jsonl` | TRIM_LINES | 400 lines | ~1/day measured | 24 months | no |
| `oapsaimi_pkpd_records.csv` | TRIM_LINES | 4 000 lines | 290–580/day | 3 months | **no** |
| `oapsaimi2_records.csv` | TRIM_LINES | 2 000 lines | ~290/day | 3 months | yes |
| `oapsaimi_wcycle.csv` | TRIM_LINES | 2 000 lines | ~290/day | 3 months | yes |
| `aimi_reactivity_analysis.csv` | TRIM_LINES | 400 lines | 48/day | 3 months | yes |
| `comparison_aimi_smb.csv` | TRIM_LINES | 2 000 lines | 0 while pref off | 3 months | yes |
| `backup_*.csv` | DROP | 7 d | file mtime | — | — |
| `oapsaimiHB_records.csv` | RETIRE | — | dead since 2024-11 | 12 months | yes |
| `oapsaimi_records.csv` | RETIRE | — | dead since 2025-04 | 12 months | yes |
| `bg.csv` | RETIRE | — | dead since 2025-02 | 12 months | yes |
| `*.overflow` | — | — | — | as parent | — |

Line budgets are set at about the same span as the time budgets above them, using the
rate measured on the device: 4 000 lines of `oapsaimi_pkpd_records.csv` is roughly a
week, 400 lines of `aimi_reactivity_analysis.csv` is roughly a week, and 400 lines of the
two daily Hormonitor files is roughly a year.

**Stale rule.** Any TRIM_TIME or TRIM_LINES file that nothing has written for 90 days is treated as RETIRE
on that pass: archived whole, then removed. This catches the next generation of dead
files without anyone having to notice them. It cannot fire on a live file, because a live
file is written every tick.

**Header rows.** A CSV whose first line is a header must keep it. For a file marked
`Header: yes`, the first line is excluded from the scan and always stays at the top of
the live file, and it is also written as the first line of every archive member, so each
archive is readable on its own. Getting this wrong would silently corrupt the file on the
first trim, so it has its own test.

### Files deliberately left unmanaged

- `autodrive_dataset.csv` — already pruned at 60 days by its own backfiller.
- Every file written with `writeText` (whole-file replacement), so already bounded: the
  `*_state.json`, `*_weights.json`, `physio_context.json`, `tpo_*.json` and the small
  `*.json.bak` copies beside them. The largest of these is under 6 KB.

## 5. How a file is trimmed

The live file keeps its name and its place, because every reader reads it by its tail.
We never split the present; we cut off the past. The whole operation streams, so peak
memory does not depend on file size. That matters: the decision file is 2.10 GB, and
`readLines()` on it would be fatal. This is the one point where the design deliberately
differs from `AutodriveDataBackfiller`, which reads its whole 6 MB file into memory.

One janitor pass over one file:

1. **Scan.** Read the file forward, line by line, tracking the byte offset. Skip the
   header line first if the policy declares one. For each line, extract its timestamp
   (see 5.1). Record the byte offset of the first line that is inside the hot window —
   call it `cutOffset` — and, for everything before it, the byte range belonging to each
   calendar month.
2. **Stop early.** If `cutOffset == 0`, nothing is out of window. Do nothing and return.
3. **Check room.** The tail copy needs about `fileLength - cutOffset` free bytes. If
   `file.usableSpace` is below that plus a margin, first evict old archives, then retry
   the check. If there is still no room, do nothing and log. A full disk must never put
   the live file at risk.
4. **Archive.** For each month range found in step 1, stream those bytes through a
   `GZIPOutputStream` opened in **append** mode on `archive/<base>_<YYYY-MM>.<ext>.gz`.
   Concatenated gzip members are valid and `GZIPInputStream` reads them back as one
   stream, so previous archives are never re-read or re-compressed.
5. **Copy the tail.** Stream bytes `[cutOffset, EOF)` into `<file>.tmp`. This is the slow
   part and it runs **without** the lock. Remember the EOF offset we stopped at.
6. **Catch up and swap, under the lock.** Take the file's lock. Append any bytes written
   past the remembered EOF onto `.tmp` — at most a few lines, since only a few minutes
   passed. `fsync`, then `renameTo` over the live file. Release the lock.
7. **Evict.** Delete archive members older than the policy's archive window, oldest
   first, until both the age limit and the size budget are met.
8. **Consume overflow files.** If the append guard (section 7) left any
   `<name>.overflow` beside the live file, archive each one whole, using the same
   month-splitting scan, then delete it. These are pure history: the writer already moved
   on to a fresh live file.

### 5.1 Getting a timestamp from a line

Only TRIM_TIME files need one, and only JSONL files qualify. The policy declares the
**key name**, because it is not the same everywhere:

- `AIMI_Decisions.jsonl`, `event_stream`, `shadow_contributions` use `"timestamp"`.
- `loop_blackbox` uses **`"wall_ms"`**. Assuming `"timestamp"` everywhere would have left
  the 70 MB blackbox file untrimmed for ever, silently.

The extractor scans the raw bytes for `"<key>":` and reads the digits after it. No JSON
parsing — parsing a 38 KB object per line just to read one number would dominate the
pass. `AimiProfileAdvisorActivity` already does exactly this, by hand, for the support
package, which confirms the approach works on this data.

Lines whose timestamp cannot be read do not get an individual decision. The scan looks
only for the **first** line that is inside the window, and the file is chronological, so
an unreadable line simply inherits the side of the cut it sits on. There is no per-line
filtering and no reordering, which is what makes the byte-range split correct.

### 5.1b Why the CSV files are trimmed by line count

None of the CSV files carries a usable epoch timestamp:

- `oapsaimi2_records.csv` starts with `dateStr`, built from
  `dateUtil.dateAndTimeString(...)`, so its format follows the device locale.
- `oapsaimi_wcycle.csv` starts with `ts` formatted as `yyyy-MM-dd HH:mm:ss`.
- `oapsaimi_pkpd_records.csv` has `dateStr` in column 0 and **epoch minutes**, not
  milliseconds, in column 1.
- `aimi_reactivity_analysis.csv` does have epoch milliseconds in column 0, and
  `comparison_aimi_smb.csv` in column 1.

Supporting four different formats, one of them locale-dependent, to serve about 100 MB
out of 2.6 GB is not worth the risk of mis-parsing and cutting the wrong lines. Every one
of these loggers writes at a fixed cadence, so a line budget expresses the same window.
The two daily Hormonitor JSONL files are in the same case: they carry `generated_at` as
an ISO string and `day_local` as a date, but no epoch field.

For a TRIM_LINES file the archive member is named by the **run** month rather than the
content month. The janitor runs daily and removes about a day at a time, so the two are
nearly the same, and no timestamp has to be parsed to name a file.

### 5.2 Why the swap is safe

Every writer opens the file, appends, and closes, on each call: `appendText` in
`AuditorJsonlExport.appendLine` and in the Hormonitor exporter's `writeToFile`. No writer
holds a long-lived file descriptor. If one did, `renameTo` would leave it writing into an
orphaned inode and those lines would be lost silently. This design depends on that
property, so any new writer on a managed file must keep it.

`HormonitorReader` caches a per-day byte-offset index, but it already invalidates on
`length` plus `lastModified`, so a trim makes it rebuild by itself. No change needed
there.

## 6. Concurrency

`AimiFileLock` generalises `AutodriveDatasetLock`: a `ReentrantLock` per file path, with
the same two entry points and the same reasoning.

- The janitor uses the blocking `withFile`, and holds it only for step 6.
- Writers on the dosing path use the zero-timeout `tryWithFile`. A telemetry line is
  cheap; a delayed dose is not. If the lock is busy, the guard is skipped and the line is
  appended anyway — the guard is an optimisation, not a correctness requirement.

## 7. Write-time guard

`AimiAppendGuard.beforeAppend(file, hardCapBytes)` exists only to stop a file growing
without limit if the janitor does not run for a long time. It must be almost free,
because `AuditorJsonlExport.appendLine` is called from the loop.

- It keeps an in-memory counter of bytes appended since the last `File.length()` call and
  only stats the file once that counter passes 1 MB. So it costs one `stat` per megabyte
  written, not one per line.
- When the file is over its hard cap, it renames it to `<name>.overflow` and lets the
  writer create a fresh file. A rename is O(1) and does no I/O on the contents. No
  compression ever happens on this path.
- The next janitor pass picks up `*.overflow` files, archives them, and deletes them.

Hard caps: **1 GB for `AIMI_Decisions.jsonl`, 256 MB for every other managed file.** At
the measured 18 MB/day, 1 GB means the janitor has been failing for about two months.
These are safety nets, not working limits: a cap set close to the real hot window would
rotate files during normal operation.

## 8. Scheduling

`AimiMlTrainingScheduler.schedule()` already owns the periodic work for this plugin. We
add one more registration next to the existing ones:

```kotlin
wm.enqueueUniquePeriodicWork(
    WORK_AIMI_RETENTION,
    ExistingPeriodicWorkPolicy.UPDATE,
    PeriodicWorkRequestBuilder<AimiRetentionWorker>(24, TimeUnit.HOURS).build(),
)
```

No constraints. The comment already in that file explains why: charging plus device-idle
almost never coincide on a real phone, and work registered with those constraints simply
never ran.

`cancel()` must cancel it too.

### First run

The first pass on an existing install has to move about 2 GB through gzip in one go.
That is minutes of work, not seconds. It runs in a worker, it never blocks the loop, and
it is idempotent, so a kill halfway through leaves the live file untouched and the next
pass redoes it. The first pass processes files in size order, largest first, so the
biggest win lands even if the worker is stopped early.

## 9. Failure handling

The loop must never be affected by the janitor:

- The janitor runs only in the worker, never on the dosing thread.
- Every file is processed independently inside its own `runCatching`. One bad file does
  not stop the others.
- The worker returns `Result.success()` even after a failure, so a broken file cannot
  cause a retry storm. The next daily pass tries again.
- The live file is never deleted, only replaced by its own tail. If any step before the
  rename fails, the `.tmp` is deleted and the live file is untouched.
- Archiving happens before trimming. If archiving fails, the trim does not happen, so no
  data is lost — the file simply stays large.

## 10. Archive location and format

Archives go into an `archive/` subdirectory of the AIMI directory, named
`<base>_<YYYY-MM>.<ext>.gz`.

A useful side effect: `AimiStorageHelper.listBackupCandidates()` only collects `.json`,
`.csv` and `.jsonl`, so `.gz` archives are automatically left out of the cloud backup
upload. That is what we want — the archive is for local forensics, not for upload.

The Hormonitor exporter can write to either the shared directory or its app-scoped
fallback, depending on permissions, so the janitor must resolve and process both
locations for those files.

## 11. Testing

Unit tests against a temporary directory, no Android dependencies:

1. A file entirely inside the window is left byte-identical.
2. The cut offset lands on a line boundary, and the tail after the swap starts with the
   first in-window line.
3. An archive written by the trim reads back through `GZIPInputStream` as exactly the
   lines that were removed.
4. Two successive trims append two gzip members to the same monthly archive, and reading
   it back yields both batches in order.
5. A batch spanning two calendar months lands in two archive files, split correctly.
6. Lines appended between the tail copy and the swap survive (the catch-up step).
7. Lines with an unreadable timestamp before the cut are archived, not dropped.
8. Archive eviction deletes the oldest month first and stops as soon as the budget is
   met.
9. When free space is below the tail size, nothing is written and the live file is
   unchanged.
10. The append guard stats the file once per megabyte, not once per line (through an
    injectable stat function).
11. Rotation at the hard cap produces an `.overflow` file, and the next pass archives it
    and deletes it.
12. Trimming a file with a header keeps the header as the first line of the live file,
    and writes it as the first line of the archive member too.
13. A DROP file older than its window is deleted, and one inside its window is left
    alone.
14. A RETIRE file is archived whole and then removed.
15. The stale rule turns a TRIM file untouched for more than 90 days into a RETIRE, and
    leaves a file written yesterday alone.
16. Peak memory during a trim does not grow with file size (asserted by trimming a file
    much larger than the test heap).
