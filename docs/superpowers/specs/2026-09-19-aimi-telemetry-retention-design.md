# AIMI telemetry retention — design

Date: 2026-09-19
Status: design approved, implementation not started

## 1. Problem

The AIMI plugin writes about 13 append-only telemetry files into the AIMI storage
directory (`Documents/AAPS` when writable, app-scoped external storage otherwise).
Only one of them has any retention at all: `autodrive_dataset.csv`, pruned at 60 days
by `AutodriveDataBackfiller`. Every other file grows without any bound, for as long as
the app runs.

The largest one is `AIMI_Decisions.jsonl`. It gets one line per loop tick (about 290
lines per day). Each line is the full `DecisionContext.toMedicalJson()` blob: 5 root
keys, about 95 fields in `baseline_state` and about 37 nested objects in `adjustments`.
The code itself calls it "this multi-hundred-KB blob", which is why it was removed from
the Nightscout device status. So this single file can add tens of megabytes per day.

At the same time, no in-app consumer reads far back:

| Reader | Window actually read |
| --- | --- |
| `T3cRuntimeHistoryReader`, `HarmoniaRuntimeHistoryReader` | last 120 lines, 400 max, 24 h |
| Support package ZIP export | 24 h |
| External viewer APK (`tools/aimi_viewer`) | 31 days |

So months of data are kept to feed a 24 hour window.

## 2. Decisions already taken

These were agreed with the user before this design was written. They are inputs, not
proposals.

1. **Keep the history, do not delete it.** Old data is compressed with gzip rather than
   dropped, so that an incident reported several weeks later can still be investigated.
2. **`AIMI_Decisions.jsonl` keeps 31 days in clear text.** That is the window the
   external viewer indexes, so the viewer needs no change. Older data is archived as
   `.gz`.
3. **Files that no in-app code reads get a short clear-text window (7 days) and are then
   archived as `.gz`.** They are not switched off, because the external viewer and
   offline analysis still use some of them.
4. **Approach C (hybrid).** A periodic janitor does all the expensive work off the
   dosing path, plus a very cheap hard-cap guard in the writers so that a run of missed
   janitor passes cannot let a file grow without limit.

### Consequence the user accepted

With a 31 day clear-text window, the live decision file stays at roughly its one-month
size forever. Compression only shrinks what is older than that. The plateau does not go
down; only the accumulated history does.

## 3. Scope

### In scope

A new `AimiRetention` package under
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/` containing:

- `AimiRetentionPolicy` — the declarative table of what is kept, per file.
- `AimiRetentionManager` — the trim/archive/evict engine.
- `AimiRetentionWorker` — the WorkManager entry point.
- `AimiFileLock` — a small generalisation of `AutodriveDatasetLock` keyed by file path.
- `AimiAppendGuard` — the cheap write-time hard cap.

Plus the call sites: `AuditorJsonlExport.appendLine`, the Hormonitor exporter's
`writeToFile`, and one new periodic work registration in `AimiMlTrainingScheduler`.

### Out of scope

- **The shape of the decision blob.** Writing ~300 KB every 5 minutes to serve a 24 hour
  window is the real cause, but changing what is logged is a separate change with its own
  forensic risk.
- **The ML training corpora** (`oapsaimiML2_records.csv`, `basal_adaptive_records.csv`).
  `AimiSmbTrainer` and `BasalMlDatasetParser` read them whole with `readLines()`, so they
  need a row cap for out-of-memory safety. That is a safety fix with different reasoning
  and a different risk profile; it is tracked separately.
- **`autodrive_dataset.csv`.** It already has retention and its own lock. Not touched.
- **The storage path inconsistency.** The writer resolves the file through
  `AimiStorageHelper`, while `T3cRuntimeHistoryReader.aimiDecisionsJsonlFile()` rebuilds
  `Documents/AAPS` by hand. If the helper falls back to app-scoped storage, the readers
  see nothing. This is a pre-existing defect, unrelated to retention, and is only recorded
  here so it is not forgotten.

## 4. Retention policy

Each managed file gets one of two operations:

- **TRIM** — cut the lines older than `hot` out of the live file and append them to a
  compressed archive. This is the algorithm in section 5.
- **DROP** — delete the whole file once it is older than `hot`. Used only for files that
  are already dead copies, never for a live telemetry stream.

`hot` is the clear-text window kept in the live file; `archive` is how long the
compressed history is kept afterwards.

| File | Op | Lines/day | Hot | Archive | Header | Reason |
| --- | --- | --- | --- | --- | --- | --- |
| `AIMI_Decisions.jsonl` | TRIM | ~290, very large | 31 d | 12 months | no | external viewer indexes 31 d |
| `AIMI_HORMONITOR_event_stream_v1.jsonl` | TRIM | ~290 | 31 d | 12 months | no | in-app viewer reads it per day |
| `AIMI_HORMONITOR_shadow_contributions_v1.jsonl` | TRIM | ~290 | 7 d | 3 months | no | no in-app reader |
| `AIMI_HORMONITOR_loop_blackbox_v1.jsonl` | TRIM | ~2000 | 7 d | 3 months | no | no in-app reader, highest line count |
| `oapsaimi_pkpd_records.csv` | TRIM | 290–580 | 7 d | 3 months | no | external viewer only |
| `oapsaimi2_records.csv` | TRIM | ~290 | 7 d | 3 months | yes | no reader found |
| `oapsaimi_wcycle.csv` | TRIM | variable | 7 d | 3 months | yes | offline analysis only |
| `aimi_reactivity_analysis.csv` | TRIM | 48 | 7 d | 3 months | yes | no reader found |
| `comparison_aimi_smb.csv` | TRIM | 0 unless enabled | 7 d | 3 months | yes | `ComparatorActivity` only |
| `backup_*.csv` | DROP | sporadic | 7 d | — | — | orphans of `removeLast200Lines`, nothing reads them |
| `*.overflow` | — | — | — | as parent | — | produced by the append guard, see section 7 |

**Header rows.** A CSV whose first line is a header must keep it. For a file marked
`Header: yes`, the first line is excluded from the scan and always stays at the top of
the live file, and it is also written as the first line of every archive member so each
archive is readable on its own. Getting this wrong would silently corrupt the file on the
first trim, so it is covered by its own test.

### Files deliberately left unmanaged

- `AIMI_HORMONITOR_daily_outcomes_v1.jsonl` (48 lines/day) and
  `AIMI_HORMONITOR_dataset_qa_v1.jsonl` (about 1 line/day). Both are small enough that a
  year costs well under a megabyte. Managing them would add code and risk for no gain.
- `autodrive_dataset.csv` — already pruned at 60 days by its own backfiller.
- Every file written with `writeText` (whole-file replacement), so already bounded: the
  `*_state.json`, `*_weights.json`, `physio_context.json`, `tpo_*.json` and similar.

## 5. How a file is trimmed

The live file keeps its name and its place, because every reader reads it by its tail.
We never split the present; we cut off the past. The whole operation streams, so peak
memory does not depend on file size — this matters, since the file can be hundreds of
megabytes and `readLines()` would be fatal.

One janitor pass over one file:

1. **Scan.** Read the file forward, line by line, tracking the byte offset. Skip the
   header line first if the policy declares one. For each
   line, extract its timestamp (see 5.1). Record the byte offset of the first line that
   is inside the hot window — call it `cutOffset` — and, for everything before it, the
   byte range belonging to each calendar month.
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

The policy declares one extractor per file:

- JSONL files: a regex on `"timestamp"\s*:\s*(\d+)`, applied to the raw line. No JSON
  parsing — parsing a 300 KB object per line just to read one number would dominate the
  pass.
- CSV files: a declared column index, parsed as epoch milliseconds.

Lines whose timestamp cannot be read do not get an individual decision. The scan looks
only for the **first** line that is inside the window, and the file is chronological, so
an unreadable line simply inherits the side of the cut it sits on. There is no per-line
filtering and no reordering, which is what makes the byte-range split correct.

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

`AimiAppendGuard.beforeAppend(file, hardCapBytes)` exists only to stop a file from
growing without limit if the janitor does not run for a long time. It must be almost
free, because `AuditorJsonlExport.appendLine` is called from the loop.

- It keeps an in-memory counter of bytes appended since the last `File.length()` call and
  only stats the file once that counter passes 1 MB. So it costs one `stat` per megabyte
  written, not one per line.
- When the file is over its hard cap, it renames it to `<name>.overflow` and lets the
  writer create a fresh file. A rename is O(1) and does no I/O on the contents. No
  compression ever happens on this path.
- The next janitor pass picks up `*.overflow` files, archives them, and deletes them.

The hard cap is a safety net, set well above the expected hot window, not a working
limit: reaching it means the janitor has been failing.

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

## 9. Failure handling

The loop must never be affected by the janitor. The rules:

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
10. The append guard stats the file once per megabyte, not once per line.
11. Rotation at the hard cap produces an `.overflow` file, and the next pass archives it
    and deletes it.
12. Trimming a file with a header keeps the header as the first line of the live file,
    and writes it as the first line of the archive member too.
13. A DROP file older than its window is deleted, and one inside its window is left
    alone.

## 12. Assumptions to confirm

These numbers come from line counts read in the code, not from a device measurement.
They must be checked against `adb shell ls -l /sdcard/Documents/AAPS/` before the values
are frozen:

- The per-line size of `AIMI_Decisions.jsonl` is taken from the code comment
  ("multi-hundred-KB"), so 31 days is estimated at a few gigabytes in the worst case. If
  the real hot window turns out to be much larger than expected, the 31 day window is
  worth revisiting with the user, since it is the whole plateau.
- The hard cap per file ships at **4 GB for `AIMI_Decisions.jsonl` and 512 MB for every
  other managed file**. These are deliberately far above any healthy value: the guard is
  there to stop runaway growth, and a cap set too close to the real hot window would
  rotate files during normal operation. Once the device measurement is in, the decision
  file's cap should be re-set to about twice its real 31 day size.
- The archive size budget (12 months for the decision file) assumes roughly 10:1
  compression on this JSON, which is typical but unverified for this particular shape.
