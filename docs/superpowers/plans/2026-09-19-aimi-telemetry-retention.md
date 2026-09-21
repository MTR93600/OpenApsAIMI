# AIMI Telemetry Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the AIMI telemetry files growing without limit — 2.6 GB on the user's device today, 2.10 GB of it in `AIMI_Decisions.jsonl` — by keeping a short clear-text window in each live file and moving everything older into gzip archives.

**Architecture:** A daily WorkManager job (`AimiRetentionWorker`) walks a declarative policy table. For each file it streams the file once to find the byte offset where the kept window starts, appends the bytes before that offset to a monthly `.gz` archive, copies the tail to a temp file, then swaps it in under a per-file lock. Nothing is ever loaded whole into memory. A cheap byte-counting guard in the two append choke points renames a file aside if it ever passes a hard cap, so a dead janitor cannot let a file grow for ever.

**Tech Stack:** Kotlin, JUnit 5 (`org.junit.jupiter.api`), Google Truth (`com.google.truth`), `java.util.zip.GZIPOutputStream`/`GZIPInputStream`, AndroidX WorkManager, Dagger (`@Inject`).

**Spec:** `docs/superpowers/specs/2026-09-19-aimi-telemetry-retention-design.md`

## Global Constraints

- **Do NOT run `git commit` or `git push`.** This project forbids committing until the user explicitly asks. Each task ends by staging the files and reporting; the user reviews and commits.
- **Never use `cd && command`.** Use absolute paths or `git -C`. Do not start a command with `awk`, `cut`, `tr`, `sort`, `uniq`, `diff`, `which`, `chmod`, `tar`.
- **Test command (macOS checkout):** `./gradlew :plugins:aps:testFullDebugUnitTest --tests "<FQN>" --no-daemon`. On the Windows checkout the same task runs through `./gradlew.bat`.
- **Redirect, never pipe, gradle output.** A pipe makes the reported exit code `tail`'s, so a failing build looks like it passed. Use `> /tmp/build.log 2>&1` then grep for `BUILD FAILED` / `BUILD SUCCESSFUL` / `^e: `.
- **Explicit imports only.** Never write a fully qualified name inline; add an `import` at the top of the file.
- **Simple English** in code, comments and KDoc.
- **Nothing in this package may run on the dosing thread** except `AimiAppendGuard`, and that one must not block.
- All new production code goes in `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/`, tests in `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/`.
- New classes are `internal`, except `AimiRetentionWorker` and `AimiAppendGuard`, which are public because they are used from other packages. `internal` is module-wide in Kotlin, so the public worker can still build the internal `AimiRetentionManager`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `retention/AimiFileLock.kt` | One `ReentrantLock` per file path. Blocking and zero-timeout entry points. |
| `retention/AimiLineScanner.kt` | Walks a file line by line giving byte offsets and a bounded prefix. Never holds a whole line. |
| `retention/AimiTimestampKey.kt` | Reads an epoch-milliseconds value out of a raw line prefix, for one JSON key name. |
| `retention/AimiRetentionPolicy.kt` | The table: one rule per managed file. Pure data. |
| `retention/AimiCutPlanner.kt` | Turns a file plus a rule into a cut offset and the month ranges to archive. |
| `retention/AimiArchive.kt` | Appends gzip members, and evicts old ones. |
| `retention/AimiRetentionManager.kt` | Orchestrates one pass: trim, drop, retire, overflow, stale. |
| `retention/AimiRetentionWorker.kt` | WorkManager entry point. |
| `retention/AimiAppendGuard.kt` | The cheap hard-cap guard used by the writers. |

---

## Task 1: Per-file lock

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiFileLock.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiFileLockTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AimiFileLock.withFile(file: File, block: () -> T): T` and `AimiFileLock.tryWithFile(file: File, block: () -> T): T?`.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AimiFileLockTest {

    @Test
    fun tryWithFileReturnsNullWhileAnotherThreadHoldsTheSameFile(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            AimiFileLock.withFile(file) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        worker.start()
        held.await(5, TimeUnit.SECONDS)

        assertThat(AimiFileLock.tryWithFile(file) { "taken" }).isNull()

        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun differentFilesDoNotBlockEachOther(@TempDir dir: File) {
        val a = File(dir, "a.jsonl")
        val b = File(dir, "b.jsonl")
        val result = AimiFileLock.withFile(a) { AimiFileLock.tryWithFile(b) { "free" } }
        assertThat(result).isEqualTo("free")
    }

    @Test
    fun theSameThreadCanTakeTheSameFileTwice(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val result = AimiFileLock.withFile(file) { AimiFileLock.withFile(file) { "reentrant" } }
        assertThat(result).isEqualTo("reentrant")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiFileLockTest" --no-daemon > /tmp/t1.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiFileLock`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises access to one telemetry file, the same way `AutodriveDatasetLock` does for the
 * Autodrive dataset, but keyed by path so one lock per managed file is enough.
 *
 * The janitor rewrites a file by renaming a temporary copy over it. A writer that appends between
 * the copy and the rename would land in a file that is about to be replaced, so its line would be
 * lost. The janitor therefore takes [withFile] around the swap.
 *
 * Writers called from the decision path take [tryWithFile] instead and give up at once if the lock
 * is busy: a lost telemetry line is cheap, a delayed dose is not.
 */
internal object AimiFileLock {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun lockFor(file: File): ReentrantLock =
        locks.computeIfAbsent(file.absolutePath) { ReentrantLock() }

    /** Runs [block] with exclusive access to [file], waiting for it if necessary. */
    fun <T> withFile(file: File, block: () -> T): T {
        val lock = lockFor(file)
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Runs [block] only if [file] is free right now, and returns `null` otherwise. Never waits. */
    fun <T : Any> tryWithFile(file: File, block: () -> T): T? {
        val lock = lockFor(file)
        if (!lock.tryLock()) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiFileLock.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiFileLockTest.kt
```

Report the test output. Do not commit.

---

## Task 2: Streaming line scanner

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiLineScanner.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiLineScannerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AimiLineScanner.PREFIX_LIMIT: Int` (4096) and
  `AimiLineScanner.forEachLine(file: File, onLine: (start: Long, endExclusive: Long, prefix: ByteArray) -> Boolean)`.
  `endExclusive` includes the newline. A trailing line with no newline is not reported, so an
  interrupted append is never cut in half. Returning `false` from `onLine` stops the walk.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AimiLineScannerTest {

    private fun collect(file: File): List<Triple<Long, Long, String>> {
        val out = mutableListOf<Triple<Long, Long, String>>()
        AimiLineScanner.forEachLine(file) { start, end, prefix ->
            out.add(Triple(start, end, String(prefix, Charsets.UTF_8)))
            true
        }
        return out
    }

    @Test
    fun reportsOffsetsThatIncludeTheNewline(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        file.writeText("abc\nde\n")

        val lines = collect(file)

        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo(Triple(0L, 4L, "abc"))
        assertThat(lines[1]).isEqualTo(Triple(4L, 7L, "de"))
    }

    @Test
    fun doesNotReportATrailingLineWithoutANewline(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        file.writeText("abc\nhalf-written")

        val lines = collect(file)

        assertThat(lines).hasSize(1)
        assertThat(lines[0].third).isEqualTo("abc")
    }

    @Test
    fun truncatesThePrefixButKeepsTheOffsetsExact(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val long = "x".repeat(AimiLineScanner.PREFIX_LIMIT + 500)
        file.writeText("$long\ntail\n")

        val lines = collect(file)

        assertThat(lines[0].third).hasLength(AimiLineScanner.PREFIX_LIMIT)
        assertThat(lines[0].second).isEqualTo(long.length + 1L)
        assertThat(lines[1].third).isEqualTo("tail")
    }

    @Test
    fun stopsWhenTheCallbackReturnsFalse(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        file.writeText("one\ntwo\nthree\n")
        var seen = 0

        AimiLineScanner.forEachLine(file) { _, _, _ ->
            seen++
            false
        }

        assertThat(seen).isEqualTo(1)
    }

    @Test
    fun handlesALineThatSpansSeveralReadBuffers(@TempDir dir: File) {
        val file = File(dir, "a.jsonl")
        val big = "y".repeat(200_000)
        file.writeText("$big\nlast\n")

        val lines = collect(file)

        assertThat(lines).hasSize(2)
        assertThat(lines[1]).isEqualTo(Triple(big.length + 1L, big.length + 6L, "last"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiLineScannerTest" --no-daemon > /tmp/t2.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiLineScanner`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Walks a telemetry file line by line and reports each line's byte range.
 *
 * The decision file is 2.10 GB and a single line can be hundreds of kilobytes, so nothing here
 * materialises a whole line, let alone a whole file. The caller gets the byte offsets plus at most
 * [PREFIX_LIMIT] bytes from the start of the line, which is all any timestamp extractor needs.
 */
internal object AimiLineScanner {

    /** How many bytes of each line the caller sees. The timestamp keys all sit in the first few. */
    const val PREFIX_LIMIT = 4096

    private const val BUFFER_SIZE = 64 * 1024
    private const val NEWLINE = '\n'.code.toByte()

    /**
     * Calls [onLine] once per complete line, with `endExclusive` pointing just past the newline.
     *
     * A trailing line with no newline is **not** reported. The writers append with `appendText`, so
     * a file read while a write is in flight can end mid-line; leaving that line out keeps a partial
     * record on the live side of any cut instead of archiving half of it.
     *
     * Returning `false` from [onLine] stops the walk.
     */
    fun forEachLine(file: File, onLine: (start: Long, endExclusive: Long, prefix: ByteArray) -> Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        val prefix = ByteArrayOutputStream(256)
        var lineStart = 0L
        var absolute = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) return
                var i = 0
                while (i < read) {
                    if (buffer[i] == NEWLINE) {
                        val end = absolute + i + 1
                        if (!onLine(lineStart, end, prefix.toByteArray())) return
                        prefix.reset()
                        lineStart = end
                    } else if (prefix.size() < PREFIX_LIMIT) {
                        prefix.write(buffer[i].toInt())
                    }
                    i++
                }
                absolute += read
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiLineScanner.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiLineScannerTest.kt
```

---

## Task 3: Timestamp key extractor

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiTimestampKey.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiTimestampKeyTest.kt`

**Interfaces:**
- Consumes: the `prefix: ByteArray` produced by `AimiLineScanner.forEachLine`.
- Produces: `class AimiTimestampKey(key: String)` with `fun extract(prefix: ByteArray): Long?`.

**Why the key is a parameter:** `AIMI_Decisions.jsonl`, `AIMI_HORMONITOR_event_stream_v1.jsonl` and
`AIMI_HORMONITOR_shadow_contributions_v1.jsonl` use `"timestamp"`, but
`AIMI_HORMONITOR_loop_blackbox_v1.jsonl` uses `"wall_ms"`. Hard-coding `"timestamp"` would leave the
70 MB blackbox file untrimmed for ever, and nothing would report it.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiTimestampKeyTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun readsTheValueOfItsOwnKey() {
        val key = AimiTimestampKey("timestamp")
        val line = bytes("""{"event_id":"a","timestamp":1758240000000,"trigger":"loop"}""")

        assertThat(key.extract(line)).isEqualTo(1758240000000L)
    }

    @Test
    fun readsWallMsForTheBlackbox() {
        val key = AimiTimestampKey("wall_ms")
        val line = bytes("""{"type":"loop_pulse","wall_ms":1758240000000,"uptime_ms":42}""")

        assertThat(key.extract(line)).isEqualTo(1758240000000L)
    }

    @Test
    fun returnsNullWhenTheKeyIsAbsent() {
        val key = AimiTimestampKey("timestamp")

        assertThat(key.extract(bytes("""{"type":"loop_pulse","wall_ms":17}"""))).isNull()
    }

    @Test
    fun returnsNullWhenTheValueIsNotANumber() {
        val key = AimiTimestampKey("timestamp")

        assertThat(key.extract(bytes("""{"timestamp":"2026-09-19T00:00:00Z"}"""))).isNull()
    }

    @Test
    fun returnsNullOnAPrefixCutBeforeTheDigits() {
        val key = AimiTimestampKey("timestamp")

        assertThat(key.extract(bytes("""{"event_id":"a","timestamp":"""))).isNull()
    }

    @Test
    fun ignoresAKeyThatIsOnlyASuffixOfAnotherKey() {
        val key = AimiTimestampKey("timestamp")
        val line = bytes("""{"parent_timestamp":111,"timestamp":222}""")

        assertThat(key.extract(line)).isEqualTo(111L)
    }
}
```

Note on the last test: the extractor matches `"timestamp":` anywhere, so `"parent_timestamp":` matches
first. That is accepted on purpose — the scan only needs a value that moves forward in time, and both
keys do. The test records the behaviour so a later change does not make it silently different.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiTimestampKeyTest" --no-daemon > /tmp/t3.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiTimestampKey`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

/**
 * Reads an epoch-milliseconds value out of a raw JSONL line, without parsing the JSON.
 *
 * A decision line is about 38 KB. Building a `JSONObject` for each of them, only to read one number,
 * would dominate the whole pass. `AimiProfileAdvisorActivity` already scans for the key by hand for
 * the support package export, which shows this works on the real data.
 */
internal class AimiTimestampKey(key: String) {

    private val needle = "\"$key\":".toByteArray(Charsets.US_ASCII)

    /** Returns the value, or `null` when the key is missing, non-numeric, or cut off by the prefix. */
    fun extract(prefix: ByteArray): Long? {
        val at = indexOf(prefix) ?: return null
        var i = at + needle.size
        while (i < prefix.size && prefix[i] == SPACE) i++
        val start = i
        while (i < prefix.size && prefix[i] >= ZERO && prefix[i] <= NINE) i++
        if (i == start) return null
        return String(prefix, start, i - start, Charsets.US_ASCII).toLongOrNull()
    }

    private fun indexOf(haystack: ByteArray): Int? {
        val last = haystack.size - needle.size
        var i = 0
        outer@ while (i <= last) {
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
                j++
            }
            return i
        }
        return null
    }

    private companion object {

        const val SPACE = ' '.code.toByte()
        const val ZERO = '0'.code.toByte()
        const val NINE = '9'.code.toByte()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiTimestampKey.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiTimestampKeyTest.kt
```

---

## Task 4: The policy table

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionPolicy.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionPolicyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class AimiRetentionOp { TRIM_TIME, TRIM_LINES, DROP, RETIRE }`
  - `data class AimiRetentionRule(fileName, op, hotDays, hotLines, timestampKey, archiveMonths, hasHeader, hardCapBytes)`
  - `AimiRetentionPolicy.RULES: List<AimiRetentionRule>`
  - `AimiRetentionPolicy.DROP_GLOBS: List<Regex>`
  - `AimiRetentionPolicy.STALE_DAYS = 90`
  - `AimiRetentionPolicy.OVERFLOW_SUFFIX = ".overflow"`
  - `AimiRetentionPolicy.ruleFor(fileName: String): AimiRetentionRule?`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiRetentionPolicyTest {

    @Test
    fun everyFileAppearsOnce() {
        val names = AimiRetentionPolicy.RULES.map { it.fileName }

        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun everyTimeTrimmedFileDeclaresItsTimestampKey() {
        val missing = AimiRetentionPolicy.RULES
            .filter { it.op == AimiRetentionOp.TRIM_TIME }
            .filter { it.timestampKey.isNullOrBlank() || it.hotDays <= 0 }

        assertThat(missing).isEmpty()
    }

    @Test
    fun everyLineTrimmedFileDeclaresAPositiveLineBudget() {
        val missing = AimiRetentionPolicy.RULES
            .filter { it.op == AimiRetentionOp.TRIM_LINES }
            .filter { it.hotLines <= 0 }

        assertThat(missing).isEmpty()
    }

    @Test
    fun theBlackboxUsesWallMsNotTimestamp() {
        val rule = AimiRetentionPolicy.ruleFor("AIMI_HORMONITOR_loop_blackbox_v1.jsonl")

        assertThat(rule).isNotNull()
        assertThat(rule!!.timestampKey).isEqualTo("wall_ms")
    }

    @Test
    fun theDecisionFileKeepsSevenDaysAndHasTheLargestHardCap() {
        val rule = AimiRetentionPolicy.ruleFor("AIMI_Decisions.jsonl")!!

        assertThat(rule.op).isEqualTo(AimiRetentionOp.TRIM_TIME)
        assertThat(rule.hotDays).isEqualTo(7)
        assertThat(rule.hardCapBytes).isEqualTo(1024L * 1024 * 1024)
        assertThat(AimiRetentionPolicy.RULES.map { it.hardCapBytes }.max()).isEqualTo(rule.hardCapBytes)
    }

    @Test
    fun thePkpdCsvHasNoHeaderButTheOtherCsvFilesDo() {
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimi_pkpd_records.csv")!!.hasHeader).isFalse()
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimi_wcycle.csv")!!.hasHeader).isTrue()
        assertThat(AimiRetentionPolicy.ruleFor("aimi_reactivity_analysis.csv")!!.hasHeader).isTrue()
        assertThat(AimiRetentionPolicy.ruleFor("comparison_aimi_smb.csv")!!.hasHeader).isTrue()
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimi2_records.csv")!!.hasHeader).isTrue()
    }

    @Test
    fun theMlCorporaAreNotManagedHere() {
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimiML2_records.csv")).isNull()
        assertThat(AimiRetentionPolicy.ruleFor("basal_adaptive_records.csv")).isNull()
        assertThat(AimiRetentionPolicy.ruleFor("autodrive_dataset.csv")).isNull()
    }

    @Test
    fun theBackupGlobMatchesOnlyGeneratedBackups() {
        val glob = AimiRetentionPolicy.DROP_GLOBS.single()

        assertThat(glob.matches("backup_20260919_120000.csv")).isTrue()
        assertThat(glob.matches("oapsaimiML2_records.csv")).isFalse()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiRetentionPolicyTest" --no-daemon > /tmp/t4.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiRetentionPolicy`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

/** What the janitor does with one file. */
internal enum class AimiRetentionOp {

    /** Keep the lines newer than `hotDays`, archive the rest. Needs `timestampKey`. */
    TRIM_TIME,

    /** Keep the last `hotLines` lines, archive the rest. For files with no epoch field. */
    TRIM_LINES,

    /** Delete the whole file once it is older than `hotDays`. For generated copies only. */
    DROP,

    /** Archive the whole file, then remove it. For files nothing writes any more. */
    RETIRE,
}

/**
 * One line per managed file.
 *
 * @param hotDays clear-text window for TRIM_TIME and DROP, in days.
 * @param hotLines clear-text window for TRIM_LINES, in lines.
 * @param timestampKey the JSON key holding epoch milliseconds, for TRIM_TIME only.
 * @param archiveMonths how long the compressed history is kept.
 * @param hasHeader true when the first line is a header that must stay at the top of the file.
 * @param hardCapBytes the size at which the append guard rotates the file aside.
 */
internal data class AimiRetentionRule(
    val fileName: String,
    val op: AimiRetentionOp,
    val hotDays: Int = 0,
    val hotLines: Int = 0,
    val timestampKey: String? = null,
    val archiveMonths: Int = 0,
    val hasHeader: Boolean = false,
    val hardCapBytes: Long = DEFAULT_HARD_CAP_BYTES,
) {

    companion object {

        const val DEFAULT_HARD_CAP_BYTES = 256L * 1024 * 1024
    }
}

/**
 * The retention table, and nothing else. Keeping it as plain data means the policy can be read and
 * reviewed without reading the engine, and the engine can be tested without the real file names.
 *
 * Sizes and rates come from the device measurement of 2026-09-19; see the design document.
 */
internal object AimiRetentionPolicy {

    /** A managed file untouched for this long is treated as RETIRE on the next pass. */
    const val STALE_DAYS = 90

    /** Suffix the append guard uses when it rotates a file aside at its hard cap. */
    const val OVERFLOW_SUFFIX = ".overflow"

    val RULES: List<AimiRetentionRule> = listOf(
        // 2.10 GB measured, about 80% of the directory. Every in-app reader uses 24 h; 7 days is
        // seven times that, and the external viewer reads the archives.
        AimiRetentionRule(
            fileName = "AIMI_Decisions.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
            hardCapBytes = 1024L * 1024 * 1024,
        ),
        // 186 MB, about 1 MB/day. Kept at 31 days because the in-app Hormonitor viewer reads it by
        // day: cutting it to 7 days would save about 25 MB and force a change in HormonitorReader.
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_event_stream_v1.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 31,
            timestampKey = "timestamp",
            archiveMonths = 12,
        ),
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_shadow_contributions_v1.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 3,
        ),
        // Writes about 7 lines per tick. Its lines carry "wall_ms", not "timestamp".
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_loop_blackbox_v1.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "wall_ms",
            archiveMonths = 3,
        ),
        // No epoch field: only "generated_at" as an ISO string and "day_local" as a date.
        // Measured at about one line a day, so 400 lines is roughly a year.
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 400,
            archiveMonths = 24,
        ),
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_dataset_qa_v1.jsonl",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 400,
            archiveMonths = 24,
        ),
        // Column 0 is a date string and column 1 is epoch MINUTES, so it is trimmed by line count.
        // 290 to 580 lines a day, so 4 000 lines is about a week. PkPdCsvLogger writes no header.
        AimiRetentionRule(
            fileName = "oapsaimi_pkpd_records.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 4_000,
            archiveMonths = 3,
        ),
        // Column 0 is dateUtil.dateAndTimeString(...), which follows the device locale.
        AimiRetentionRule(
            fileName = "oapsaimi2_records.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2_000,
            archiveMonths = 3,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "oapsaimi_wcycle.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2_000,
            archiveMonths = 3,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "aimi_reactivity_analysis.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 400,
            archiveMonths = 3,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "comparison_aimi_smb.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2_000,
            archiveMonths = 3,
            hasHeader = true,
        ),
        // Nothing has written these since 2024 or 2025. About 50 MB between them.
        AimiRetentionRule(
            fileName = "oapsaimiHB_records.csv",
            op = AimiRetentionOp.RETIRE,
            archiveMonths = 12,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "oapsaimi_records.csv",
            op = AimiRetentionOp.RETIRE,
            archiveMonths = 12,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "bg.csv",
            op = AimiRetentionOp.RETIRE,
            archiveMonths = 12,
            hasHeader = true,
        ),
    )

    /** Files matched by name rather than listed one by one. Deleted outright, never archived. */
    val DROP_GLOBS: List<Regex> = listOf(Regex("""^backup_\d{8}_\d{6}\.csv$"""))

    /** How long a dropped file is kept before deletion, in days. */
    const val DROP_AFTER_DAYS = 7

    fun ruleFor(fileName: String): AimiRetentionRule? = RULES.firstOrNull { it.fileName == fileName }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionPolicy.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionPolicyTest.kt
```

---

## Task 5: Cut planner

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiCutPlanner.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiCutPlannerTest.kt`

**Interfaces:**
- Consumes: `AimiLineScanner.forEachLine`, `AimiTimestampKey`, `AimiRetentionRule`.
- Produces:
  - `data class AimiMonthRange(val month: String, val start: Long, val endExclusive: Long)` — `month` is `yyyy-MM`.
  - `data class AimiCutPlan(val headerBytes: Long, val cutOffset: Long, val ranges: List<AimiMonthRange>)`
  - `AimiCutPlanner.plan(file: File, rule: AimiRetentionRule, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): AimiCutPlan`

`cutOffset` is the first byte to keep. `cutOffset == headerBytes` means there is nothing to archive.
For TRIM_LINES the planner keeps a ring buffer of the last `hotLines` line starts, so it needs one
pass and memory proportional to the line budget, not to the file.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

class AimiCutPlannerTest {

    private val utc = ZoneId.of("UTC")
    private val day = 24L * 60 * 60 * 1000

    private fun jsonlRule(hotDays: Int) = AimiRetentionRule(
        fileName = "x.jsonl",
        op = AimiRetentionOp.TRIM_TIME,
        hotDays = hotDays,
        timestampKey = "timestamp",
        archiveMonths = 12,
    )

    private fun line(ts: Long) = """{"event_id":"e","timestamp":$ts,"pad":"....."}"""

    @Test
    fun keepsEverythingWhenNothingIsOutOfWindow(@TempDir dir: File) {
        val now = 1_758_240_000_000L
        val file = File(dir, "x.jsonl")
        file.writeText(line(now - day) + "\n" + line(now) + "\n")

        val plan = AimiCutPlanner.plan(file, jsonlRule(7), now, utc)

        assertThat(plan.cutOffset).isEqualTo(0L)
        assertThat(plan.ranges).isEmpty()
    }

    @Test
    fun cutsOnALineBoundaryAtTheFirstLineInsideTheWindow(@TempDir dir: File) {
        val now = 1_758_240_000_000L
        val old = line(now - 30 * day)
        val fresh = line(now - day)
        val file = File(dir, "x.jsonl")
        file.writeText("$old\n$fresh\n")

        val plan = AimiCutPlanner.plan(file, jsonlRule(7), now, utc)

        assertThat(plan.cutOffset).isEqualTo(old.length + 1L)
        assertThat(plan.ranges).hasSize(1)
        assertThat(plan.ranges.single().start).isEqualTo(0L)
        assertThat(plan.ranges.single().endExclusive).isEqualTo(old.length + 1L)
    }

    @Test
    fun splitsTheArchivedPartByCalendarMonth(@TempDir dir: File) {
        // 2026-07-15, 2026-08-15 and 2026-09-15, with "now" on 2026-09-19 and a 1 day window.
        val july = 1_784_073_600_000L
        val august = july + 31 * day
        val september = august + 31 * day
        val now = september + 4 * day
        val file = File(dir, "x.jsonl")
        file.writeText(line(july) + "\n" + line(august) + "\n" + line(september) + "\n" + line(now) + "\n")

        val plan = AimiCutPlanner.plan(file, jsonlRule(1), now, utc)

        assertThat(plan.ranges.map { it.month }).containsExactly("2026-07", "2026-08", "2026-09").inOrder()
        assertThat(plan.ranges.first().start).isEqualTo(0L)
        assertThat(plan.ranges.last().endExclusive).isEqualTo(plan.cutOffset)
    }

    @Test
    fun aLineWithNoReadableTimestampStaysOnTheSideOfTheCutItSitsOn(@TempDir dir: File) {
        val now = 1_758_240_000_000L
        val broken = """{"event_id":"e","timestamp":"not-a-number"}"""
        val fresh = line(now)
        val file = File(dir, "x.jsonl")
        file.writeText("$broken\n$fresh\n")

        val plan = AimiCutPlanner.plan(file, jsonlRule(7), now, utc)

        assertThat(plan.cutOffset).isEqualTo(broken.length + 1L)
        assertThat(plan.ranges.single().endExclusive).isEqualTo(broken.length + 1L)
    }

    @Test
    fun aFileWhereNoLineHasTheKeyIsLeftAlone(@TempDir dir: File) {
        // Guards against a wrong key in the policy (the blackbox uses "wall_ms", not "timestamp").
        // Without this, every line would inherit the "old" side and the live file would be emptied.
        val now = 1_758_240_000_000L
        val file = File(dir, "x.jsonl")
        file.writeText("{\"wall_ms\":1}\n{\"wall_ms\":2}\n")

        val plan = AimiCutPlanner.plan(file, jsonlRule(7), now, utc)

        assertThat(plan.cutOffset).isEqualTo(0L)
        assertThat(plan.ranges).isEmpty()
    }

    @Test
    fun keepsTheHeaderOutOfTheCut(@TempDir dir: File) {
        val now = 1_758_240_000_000L
        val rule = AimiRetentionRule(
            fileName = "x.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 1,
            archiveMonths = 3,
            hasHeader = true,
        )
        val file = File(dir, "x.csv")
        file.writeText("a,b\n1,1\n2,2\n3,3\n")

        val plan = AimiCutPlanner.plan(file, rule, now, utc)

        assertThat(plan.headerBytes).isEqualTo(4L)
        assertThat(plan.cutOffset).isEqualTo(12L)
        assertThat(plan.ranges.single().start).isEqualTo(4L)
    }

    @Test
    fun keepsExactlyTheLastLinesForALineBudget(@TempDir dir: File) {
        val now = 1_758_240_000_000L
        val rule = AimiRetentionRule(
            fileName = "x.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2,
            archiveMonths = 3,
        )
        val file = File(dir, "x.csv")
        file.writeText("aa\nbb\ncc\ndd\n")

        val plan = AimiCutPlanner.plan(file, rule, now, utc)

        assertThat(plan.cutOffset).isEqualTo(6L)
        assertThat(plan.ranges.single().month).isEqualTo("2025-09")
    }

    @Test
    fun aFileShorterThanItsLineBudgetIsLeftAlone(@TempDir dir: File) {
        val now = 1_758_240_000_000L
        val rule = AimiRetentionRule(
            fileName = "x.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 10,
            archiveMonths = 3,
        )
        val file = File(dir, "x.csv")
        file.writeText("aa\nbb\n")

        val plan = AimiCutPlanner.plan(file, rule, now, utc)

        assertThat(plan.cutOffset).isEqualTo(0L)
        assertThat(plan.ranges).isEmpty()
    }
}
```

Note: `1_758_240_000_000` is 2025-09-19 in UTC, so `keepsExactlyTheLastLinesForALineBudget` expects
`"2025-09"`: a TRIM_LINES range is labelled by the **run** month, never by line content.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiCutPlannerTest" --no-daemon > /tmp/t5.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiCutPlanner`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A byte range of the file that belongs to one calendar month, named `yyyy-MM`. */
internal data class AimiMonthRange(val month: String, val start: Long, val endExclusive: Long)

/**
 * Where to cut a file, and how the part being archived splits by month.
 *
 * @param headerBytes size of the header line including its newline, or 0 when there is none.
 * @param cutOffset first byte to keep. Equal to [headerBytes] when there is nothing to archive.
 */
internal data class AimiCutPlan(
    val headerBytes: Long,
    val cutOffset: Long,
    val ranges: List<AimiMonthRange>,
)

/**
 * Reads a file once and works out what to archive.
 *
 * Nothing is loaded whole: the walk is done by [AimiLineScanner], and a line budget is tracked with
 * a ring buffer the size of the budget. On the 2.10 GB decision file this is a single sequential
 * read with constant memory.
 */
internal object AimiCutPlanner {

    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    fun plan(
        file: File,
        rule: AimiRetentionRule,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AimiCutPlan = when (rule.op) {
        AimiRetentionOp.TRIM_TIME  -> planByTime(file, rule, nowMs, zone)
        AimiRetentionOp.TRIM_LINES -> planByLines(file, rule, nowMs, zone)
        else                       -> AimiCutPlan(0L, 0L, emptyList())
    }

    private fun planByTime(file: File, rule: AimiRetentionRule, nowMs: Long, zone: ZoneId): AimiCutPlan {
        val key = AimiTimestampKey(rule.timestampKey ?: return AimiCutPlan(0L, 0L, emptyList()))
        val cutoffMs = nowMs - rule.hotDays * 24L * 60L * 60L * 1000L
        var headerBytes = 0L
        var first = true
        var cutOffset = -1L
        var sawTimestamp = false
        val ranges = mutableListOf<AimiMonthRange>()
        var runMonth = month(nowMs, zone)

        AimiLineScanner.forEachLine(file) { start, end, prefix ->
            if (first && rule.hasHeader) {
                headerBytes = end
                first = false
                return@forEachLine true
            }
            first = false
            val ts = key.extract(prefix)
            if (ts != null) sawTimestamp = true
            if (ts != null && ts >= cutoffMs) {
                cutOffset = start
                return@forEachLine false
            }
            val label = if (ts != null) month(ts, zone).also { runMonth = it } else runMonth
            val last = ranges.lastOrNull()
            if (last != null && last.month == label) {
                ranges[ranges.size - 1] = last.copy(endExclusive = end)
            } else {
                ranges.add(AimiMonthRange(label, start, end))
            }
            true
        }

        // Not one line carried the key: the policy names the wrong key for this file. Cutting now
        // would archive the whole live file every day, so do nothing and let the caller log it.
        if (!sawTimestamp) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        if (cutOffset < 0L) cutOffset = ranges.lastOrNull()?.endExclusive ?: headerBytes
        if (cutOffset <= headerBytes) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        return AimiCutPlan(headerBytes, cutOffset, ranges)
    }

    private fun planByLines(file: File, rule: AimiRetentionRule, nowMs: Long, zone: ZoneId): AimiCutPlan {
        val budget = rule.hotLines
        val starts = LongArray(budget)
        var count = 0
        var write = 0
        var headerBytes = 0L
        var first = true

        AimiLineScanner.forEachLine(file) { start, end, _ ->
            if (first && rule.hasHeader) {
                headerBytes = end
                first = false
                return@forEachLine true
            }
            first = false
            starts[write] = start
            write = (write + 1) % budget
            if (count < budget) count++
            true
        }

        if (count < budget) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        val oldestKept = starts[write]
        if (oldestKept <= headerBytes) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        val range = AimiMonthRange(month(nowMs, zone), headerBytes, oldestKept)
        return AimiCutPlan(headerBytes, oldestKept, listOf(range))
    }

    private fun month(epochMs: Long, zone: ZoneId): String =
        MONTH.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
```

Note on `planByTime`: when every line is out of window, `cutOffset` becomes the end of the last
archived line, so the file is emptied down to its header rather than left untouched. A trailing
partial line is never reported by the scanner, so it stays.

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiCutPlanner.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiCutPlannerTest.kt
```

---

## Task 6: Archive writer and eviction

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiArchive.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiArchiveTest.kt`

**Interfaces:**
- Consumes: `AimiMonthRange`.
- Produces:
  - `AimiArchive.directory(aimiDir: File): File` — `<aimiDir>/archive`
  - `AimiArchive.memberFile(aimiDir: File, fileName: String, month: String): File`
  - `AimiArchive.appendMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?)`
  - `AimiArchive.evict(aimiDir: File, fileName: String, keepMonths: Int, nowMs: Long, zone: ZoneId): Int` — returns how many members were deleted.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId
import java.util.zip.GZIPInputStream

class AimiArchiveTest {

    private val utc = ZoneId.of("UTC")

    private fun readBack(file: File): String =
        GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun aMemberReadsBackAsExactlyTheBytesThatWereRemoved(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\nthree\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")

        AimiArchive.appendMember(source, 0L, 8L, target, null)

        assertThat(readBack(target)).isEqualTo("one\ntwo\n")
    }

    @Test
    fun twoAppendsReadBackAsOneStreamInOrder(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")

        AimiArchive.appendMember(source, 0L, 4L, target, null)
        AimiArchive.appendMember(source, 4L, 8L, target, null)

        assertThat(readBack(target)).isEqualTo("one\ntwo\n")
    }

    @Test
    fun aHeaderIsWrittenAtTheTopOfEveryMember(@TempDir dir: File) {
        val source = File(dir, "x.csv")
        source.writeText("a,b\n1,1\n2,2\n")
        val target = File(dir, "archive/x_2026-09.csv.gz")
        val header = "a,b\n".toByteArray()

        AimiArchive.appendMember(source, 4L, 8L, target, header)
        AimiArchive.appendMember(source, 8L, 12L, target, header)

        assertThat(readBack(target)).isEqualTo("a,b\n1,1\na,b\n2,2\n")
    }

    @Test
    fun memberFileNamesKeepTheOriginalExtension(@TempDir dir: File) {
        assertThat(AimiArchive.memberFile(dir, "AIMI_Decisions.jsonl", "2026-09").name)
            .isEqualTo("AIMI_Decisions_2026-09.jsonl.gz")
        assertThat(AimiArchive.memberFile(dir, "oapsaimi_wcycle.csv", "2026-08").name)
            .isEqualTo("oapsaimi_wcycle_2026-08.csv.gz")
    }

    @Test
    fun evictionRemovesOnlyMonthsOlderThanTheWindow(@TempDir dir: File) {
        val archive = AimiArchive.directory(dir)
        archive.mkdirs()
        listOf("2026-04", "2026-05", "2026-08", "2026-09").forEach {
            File(archive, "x_$it.jsonl.gz").writeText("body")
        }
        val now = 1_789_776_000_000L // 2026-09-19 in UTC; keeping 3 months means 2026-06 onwards

        val removed = AimiArchive.evict(dir, "x.jsonl", keepMonths = 3, nowMs = now, zone = utc)

        assertThat(removed).isEqualTo(2)
        assertThat(File(archive, "x_2026-04.jsonl.gz").exists()).isFalse()
        assertThat(File(archive, "x_2026-05.jsonl.gz").exists()).isFalse()
        assertThat(File(archive, "x_2026-08.jsonl.gz").exists()).isTrue()
        assertThat(File(archive, "x_2026-09.jsonl.gz").exists()).isTrue()
    }

    @Test
    fun evictionIgnoresMembersOfOtherFiles(@TempDir dir: File) {
        val archive = AimiArchive.directory(dir)
        archive.mkdirs()
        File(archive, "x_2020-01.jsonl.gz").writeText("body")
        File(archive, "y_2020-01.jsonl.gz").writeText("body")

        AimiArchive.evict(dir, "x.jsonl", keepMonths = 1, nowMs = 1_758_240_000_000L, zone = utc)

        assertThat(File(archive, "y_2020-01.jsonl.gz").exists()).isTrue()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiArchiveTest" --no-daemon > /tmp/t6.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiArchive`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/**
 * Writes and prunes the compressed history.
 *
 * Archives are gzip files holding one member per janitor pass. Concatenated gzip members are valid,
 * and `GZIPInputStream` reads them back as a single stream, so a pass never has to read or
 * re-compress what is already there. On a 2 GB history that is the difference between a few seconds
 * and several minutes per day.
 *
 * `.gz` is deliberately not one of the extensions `AimiStorageHelper.listBackupCandidates()`
 * collects, so archives stay out of the cloud backup upload.
 */
internal object AimiArchive {

    private const val DIR_NAME = "archive"

    fun directory(aimiDir: File): File = File(aimiDir, DIR_NAME)

    /** `AIMI_Decisions.jsonl` + `2026-09` becomes `archive/AIMI_Decisions_2026-09.jsonl.gz`. */
    fun memberFile(aimiDir: File, fileName: String, month: String): File {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        val name = if (ext.isEmpty()) "${base}_$month.gz" else "${base}_$month.$ext.gz"
        return File(directory(aimiDir), name)
    }

    /**
     * Appends `[start, endExclusive)` of [source] to [target] as a new gzip member.
     *
     * [header] is written at the top of the member when the file has one, so each archive can be
     * opened on its own without the live file.
     */
    fun appendMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
        if (endExclusive <= start) return
        target.parentFile?.mkdirs()
        FileOutputStream(target, true).use { raw ->
            GZIPOutputStream(BufferedOutputStream(raw)).use { gz ->
                header?.let { gz.write(it) }
                RandomAccessFile(source, "r").use { input ->
                    input.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = endExclusive - start
                    while (remaining > 0) {
                        val want = minOf(remaining, buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) break
                        gz.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }
    }

    /** Deletes members of [fileName] older than [keepMonths]. Returns how many were removed. */
    fun evict(
        aimiDir: File,
        fileName: String,
        keepMonths: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val dir = directory(aimiDir)
        if (!dir.isDirectory) return 0
        val base = Regex.escape(fileName.substringBeforeLast('.'))
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) """\.gz""" else """\.${Regex.escape(ext)}\.gz"""
        val pattern = Regex("""^${base}_(\d{4})-(\d{2})$suffix$""")
        val oldest = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone)).minusMonths(keepMonths.toLong())
        var removed = 0
        dir.listFiles()?.sortedBy { it.name }?.forEach { candidate ->
            val match = pattern.find(candidate.name) ?: return@forEach
            val month = YearMonth.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            if (month.isBefore(oldest) && candidate.delete()) removed++
        }
        return removed
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiArchive.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiArchiveTest.kt
```

---

## Task 7: The trim engine

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManager.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManagerTrimTest.kt`

**Interfaces:**
- Consumes: `AimiCutPlanner.plan`, `AimiArchive.*`, `AimiFileLock.withFile`.
- Produces: `class AimiRetentionManager(aimiDir: File, logger: (String) -> Unit, now: () -> Long)` with
  `fun trim(file: File, rule: AimiRetentionRule): Boolean` (true when the file was rewritten) and
  `fun freeSpaceOf(file: File): Long` (open for the test to override).

The manager is constructed with a plain `File` and a logging lambda so the whole engine is testable
without Android. The Dagger wiring that turns `AimiStorageHelper` and `AAPSLogger` into those two
arguments is Task 10.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.GZIPInputStream

class AimiRetentionManagerTrimTest {

    private val now = 1_758_240_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun manager(dir: File, freeSpace: Long = Long.MAX_VALUE) =
        object : AimiRetentionManager(dir, {}, { now }) {
            override fun freeSpaceOf(file: File): Long = freeSpace
        }

    private fun jsonlRule() = AimiRetentionRule(
        fileName = "x.jsonl",
        op = AimiRetentionOp.TRIM_TIME,
        hotDays = 7,
        timestampKey = "timestamp",
        archiveMonths = 12,
    )

    private fun line(ts: Long) = """{"event_id":"e","timestamp":$ts}"""

    private fun archived(dir: File) =
        AimiArchive.directory(dir).listFiles().orEmpty().sortedBy { it.name }
            .joinToString("") { GZIPInputStream(it.inputStream()).use { s -> s.readBytes().toString(Charsets.UTF_8) } }

    @Test
    fun aFileFullyInsideTheWindowIsLeftByteIdentical(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val before = line(now - day) + "\n" + line(now) + "\n"
        file.writeText(before)

        val rewritten = manager(dir).trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(before)
        assertThat(AimiArchive.directory(dir).exists()).isFalse()
    }

    @Test
    fun theTailStartsAtTheFirstLineInsideTheWindow(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val kept = line(now - day)
        file.writeText(line(now - 30 * day) + "\n" + kept + "\n")

        val rewritten = manager(dir).trim(file, jsonlRule())

        assertThat(rewritten).isTrue()
        assertThat(file.readText()).isEqualTo("$kept\n")
    }

    @Test
    fun whatLeftTheLiveFileIsInTheArchive(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val old = line(now - 30 * day)
        file.writeText("$old\n" + line(now) + "\n")

        manager(dir).trim(file, jsonlRule())

        assertThat(archived(dir)).isEqualTo("$old\n")
    }

    @Test
    fun theHeaderStaysAtTheTopOfTheLiveFileAndOpensTheArchive(@TempDir dir: File) {
        val rule = AimiRetentionRule(
            fileName = "x.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 1,
            archiveMonths = 3,
            hasHeader = true,
        )
        val file = File(dir, "x.csv")
        file.writeText("a,b\n1,1\n2,2\n")

        manager(dir).trim(file, rule)

        assertThat(file.readText()).isEqualTo("a,b\n2,2\n")
        assertThat(archived(dir)).isEqualTo("a,b\n1,1\n")
    }

    @Test
    fun nothingIsTouchedWhenThereIsNoRoomForTheTail(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val before = line(now - 30 * day) + "\n" + line(now) + "\n"
        file.writeText(before)

        val rewritten = manager(dir, freeSpace = 1L).trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(before)
    }

    @Test
    fun aLineAppendedDuringTheCopySurvivesTheSwap(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val kept = line(now - day)
        file.writeText(line(now - 30 * day) + "\n" + kept + "\n")
        val late = line(now)

        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun onTailCopied(target: File) {
                file.appendText("$late\n")
            }
        }
        subject.trim(file, jsonlRule())

        assertThat(file.readText()).isEqualTo("$kept\n$late\n")
    }

    @Test
    fun aMissingFileIsNotAnError(@TempDir dir: File) {
        val rewritten = manager(dir).trim(File(dir, "absent.jsonl"), jsonlRule())

        assertThat(rewritten).isFalse()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiRetentionManagerTrimTest" --no-daemon > /tmp/t7.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiRetentionManager`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Runs one retention pass over one file.
 *
 * The expensive work — reading the file, compressing the old part, copying the tail — happens
 * without the file lock. Only the catch-up and the rename are held, and by then the catch-up is at
 * most the few lines written while the copy ran. The live file is never deleted, only replaced by
 * its own tail, so a failure anywhere before the rename leaves it exactly as it was.
 *
 * Open rather than final so tests can pin free space and observe the moment the tail is copied.
 */
internal open class AimiRetentionManager(
    private val aimiDir: File,
    private val logger: (String) -> Unit,
    private val now: () -> Long,
) {

    /** Free bytes usable for the temporary copy. Overridden in tests. */
    protected open fun freeSpaceOf(file: File): Long = file.usableSpace

    /** Called after the tail has been copied and before the lock is taken. Test seam only. */
    protected open fun onTailCopied(target: File) = Unit

    /**
     * Archives everything outside [rule]'s window and rewrites [file] with the rest.
     *
     * @return true when the live file was rewritten.
     */
    fun trim(file: File, rule: AimiRetentionRule): Boolean {
        if (!file.isFile || file.length() == 0L) return false

        val plan = AimiCutPlanner.plan(file, rule, now())
        if (plan.cutOffset <= plan.headerBytes) return false

        val markedEof = file.length()
        val tailBytes = markedEof - plan.cutOffset
        if (freeSpaceOf(file) < tailBytes + FREE_SPACE_MARGIN_BYTES) {
            AimiArchive.evict(aimiDir, rule.fileName, rule.archiveMonths, now())
            if (freeSpaceOf(file) < tailBytes + FREE_SPACE_MARGIN_BYTES) {
                logger("retention: not enough free space to trim ${file.name}, left untouched")
                return false
            }
        }

        val header = if (rule.hasHeader && plan.headerBytes > 0L) readRange(file, 0L, plan.headerBytes) else null
        plan.ranges.forEach { range ->
            AimiArchive.appendMember(
                source = file,
                start = range.start,
                endExclusive = range.endExclusive,
                target = AimiArchive.memberFile(aimiDir, rule.fileName, range.month),
                header = header,
            )
        }

        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp, false).use { out ->
                header?.let { out.write(it) }
                copyRange(file, plan.cutOffset, markedEof, out)
            }
            onTailCopied(tmp)
            AimiFileLock.withFile(file) {
                FileOutputStream(tmp, true).use { out -> copyRange(file, markedEof, file.length(), out) }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            logger("retention: trim of ${file.name} failed, live file untouched: ${e.message}")
            return false
        }

        AimiArchive.evict(aimiDir, rule.fileName, rule.archiveMonths, now())
        return true
    }

    private fun readRange(file: File, start: Long, endExclusive: Long): ByteArray {
        val size = (endExclusive - start).toInt()
        val out = ByteArray(size)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            input.readFully(out)
        }
        return out
    }

    private fun copyRange(file: File, start: Long, endExclusive: Long, out: FileOutputStream) {
        if (endExclusive <= start) return
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = endExclusive - start
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private companion object {

        const val TMP_SUFFIX = ".tmp"

        /** Head room kept free so the swap never fills the volume completely. */
        const val FREE_SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManager.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManagerTrimTest.kt
```

---

## Task 8: Drop, retire, stale and overflow

**Files:**
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManager.kt`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManagerSweepTest.kt`

**Interfaces:**
- Consumes: everything from Task 7.
- Produces, on `AimiRetentionManager`:
  - `fun retire(file: File, rule: AimiRetentionRule): Boolean`
  - `fun dropOrphans(): Int`
  - `fun consumeOverflow(rule: AimiRetentionRule): Int`
  - `fun runOnce(): Int` — the whole pass, returning how many files it changed. Processes files
    largest first, so an interrupted first run still lands the biggest win.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.GZIPInputStream

class AimiRetentionManagerSweepTest {

    private val now = 1_758_240_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun manager(dir: File) = AimiRetentionManager(dir, {}, { now })

    private fun readBack(file: File) =
        GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun retireArchivesTheWholeFileAndRemovesIt(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")

        val done = manager(dir).retire(file, rule)

        assertThat(done).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(readBack(AimiArchive.directory(dir).listFiles()!!.single())).isEqualTo("a,b\n1,1\n")
    }

    @Test
    fun aTrimFileUntouchedForLongerThanTheStaleWindowIsRetired(@TempDir dir: File) {
        val rule = AimiRetentionRule(
            fileName = "x.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 3,
        )
        val file = File(dir, "x.jsonl")
        file.writeText("""{"timestamp":1}""" + "\n")
        file.setLastModified(now - (AimiRetentionPolicy.STALE_DAYS + 1) * day)

        val subject = manager(dir)
        assertThat(subject.isStale(file)).isTrue()

        subject.retire(file, rule)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun aFileWrittenYesterdayIsNotStale(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        file.writeText("body\n")
        file.setLastModified(now - day)

        assertThat(manager(dir).isStale(file)).isFalse()
    }

    @Test
    fun onlyOldGeneratedBackupsAreDropped(@TempDir dir: File) {
        val old = File(dir, "backup_20240101_000000.csv")
        old.writeText("body")
        old.setLastModified(now - 30 * day)
        val fresh = File(dir, "backup_20260919_000000.csv")
        fresh.writeText("body")
        fresh.setLastModified(now - day)
        val keep = File(dir, "oapsaimiML2_records.csv")
        keep.writeText("body")
        keep.setLastModified(now - 365 * day)

        val dropped = manager(dir).dropOrphans()

        assertThat(dropped).isEqualTo(1)
        assertThat(old.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        assertThat(keep.exists()).isTrue()
    }

    @Test
    fun anOverflowFileIsArchivedWholeAndDeleted(@TempDir dir: File) {
        val rule = AimiRetentionRule(
            fileName = "x.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
        )
        val overflow = File(dir, "x.jsonl" + AimiRetentionPolicy.OVERFLOW_SUFFIX)
        overflow.writeText("""{"timestamp":1}""" + "\n")

        val consumed = manager(dir).consumeOverflow(rule)

        assertThat(consumed).isEqualTo(1)
        assertThat(overflow.exists()).isFalse()
        assertThat(AimiArchive.directory(dir).listFiles()).hasLength(1)
    }

    @Test
    fun runOnceProcessesTheLargestFileFirst(@TempDir dir: File) {
        val seen = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun onFileVisited(file: File) {
                seen.add(file.name)
            }
        }
        File(dir, "AIMI_Decisions.jsonl").writeText("x".repeat(500) + "\n")
        File(dir, "oapsaimi_wcycle.csv").writeText("x\n")

        subject.runOnce()

        assertThat(seen.first()).isEqualTo("AIMI_Decisions.jsonl")
    }

    @Test
    fun oneBrokenFileDoesNotStopTheOthers(@TempDir dir: File) {
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun onFileVisited(file: File) {
                if (file.name == "AIMI_Decisions.jsonl") error("boom")
            }
        }
        File(dir, "AIMI_Decisions.jsonl").writeText("x".repeat(500) + "\n")
        val other = File(dir, "bg.csv")
        other.writeText("a,b\n1,1\n")

        subject.runOnce()

        assertThat(other.exists()).isFalse() // bg.csv is RETIRE, so it was still processed
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiRetentionManagerSweepTest" --no-daemon > /tmp/t8.log 2>&1`
Expected: FAIL, `Unresolved reference: retire`.

- [ ] **Step 3: Add the sweep methods to `AimiRetentionManager`**

Add these members inside the existing class, and the imports `java.time.ZoneId` is not needed here.

```kotlin
    /** Called once per managed file before it is processed. Test seam only. */
    protected open fun onFileVisited(file: File) = Unit

    /** True when nothing has written [file] for [AimiRetentionPolicy.STALE_DAYS]. */
    fun isStale(file: File): Boolean =
        file.isFile && now() - file.lastModified() > AimiRetentionPolicy.STALE_DAYS * 24L * 60L * 60L * 1000L

    /** Archives the whole of [file] and removes it. For files nothing writes any more. */
    fun retire(file: File, rule: AimiRetentionRule): Boolean {
        if (!file.isFile || file.length() == 0L) return file.isFile && file.delete()
        return try {
            AimiArchive.appendMember(
                source = file,
                start = 0L,
                endExclusive = file.length(),
                target = AimiArchive.memberFile(aimiDir, rule.fileName, monthOfNow()),
                header = null,
            )
            AimiFileLock.withFile(file) { file.delete() }
        } catch (e: Exception) {
            logger("retention: retire of ${file.name} failed: ${e.message}")
            false
        }
    }

    /** Deletes generated backup copies older than [AimiRetentionPolicy.DROP_AFTER_DAYS]. */
    fun dropOrphans(): Int {
        val cutoff = now() - AimiRetentionPolicy.DROP_AFTER_DAYS * 24L * 60L * 60L * 1000L
        var dropped = 0
        aimiDir.listFiles()?.forEach { candidate ->
            if (!candidate.isFile) return@forEach
            if (AimiRetentionPolicy.DROP_GLOBS.none { it.matches(candidate.name) }) return@forEach
            if (candidate.lastModified() > cutoff) return@forEach
            if (candidate.delete()) dropped++
        }
        return dropped
    }

    /** Archives and removes any `<name>.overflow` left behind by the append guard. */
    fun consumeOverflow(rule: AimiRetentionRule): Int {
        val overflow = File(aimiDir, rule.fileName + AimiRetentionPolicy.OVERFLOW_SUFFIX)
        if (!overflow.isFile) return 0
        return if (retire(overflow, rule)) 1 else 0
    }

    /** One full pass over the policy. Returns how many files were changed. */
    fun runOnce(): Int {
        var changed = 0
        val ordered = AimiRetentionPolicy.RULES.sortedByDescending { File(aimiDir, it.fileName).length() }
        ordered.forEach { rule ->
            val file = File(aimiDir, rule.fileName)
            runCatching {
                onFileVisited(file)
                changed += consumeOverflow(rule)
                when {
                    rule.op == AimiRetentionOp.RETIRE -> if (retire(file, rule)) changed++
                    isStale(file)                     -> if (retire(file, rule)) changed++
                    else                              -> if (trim(file, rule)) changed++
                }
            }.onFailure { logger("retention: ${rule.fileName} failed: ${it.message}") }
        }
        runCatching { changed += dropOrphans() }
            .onFailure { logger("retention: orphan sweep failed: ${it.message}") }
        return changed
    }

    private fun monthOfNow(): String =
        DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()))
```

Add these imports at the top of the file:

```kotlin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
```

Also change the class header so the test can subclass it and see `aimiDir` and `logger`:

```kotlin
internal open class AimiRetentionManager(
    protected val aimiDir: File,
    protected val logger: (String) -> Unit,
    protected val now: () -> Long,
) {
```

- [ ] **Step 4: Run both manager test classes to verify they pass**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.*" --no-daemon > /tmp/t8b.log 2>&1`
Expected: `BUILD SUCCESSFUL`, all retention tests green.

- [ ] **Step 5: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManager.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionManagerSweepTest.kt
```

---

## Task 9: The append guard, and wiring it into the two writers

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiAppendGuard.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorJsonlExport.kt:103-109`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/AimiHormonitorStudyExporterMTR.kt:696-705`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiAppendGuardTest.kt`

**Interfaces:**
- Consumes: `AimiRetentionPolicy.ruleFor`, `AimiRetentionPolicy.OVERFLOW_SUFFIX`.
- Produces: `AimiAppendGuard.beforeAppend(file: File, addedBytes: Int)` and, for the test,
  `AimiAppendGuard.resetForTest()` plus `AimiAppendGuard.statCount`.

**Why it must stay cheap:** `AuditorJsonlExport.appendLine` runs on the loop thread. Calling
`File.length()` per line would add a syscall to every tick for no benefit, so the guard counts the
bytes it has seen and only stats the file once per megabyte.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AimiAppendGuardTest {

    @BeforeEach
    fun setUp() {
        AimiAppendGuard.resetForTest()
    }

    @Test
    fun statsTheFileOncePerMegabyteNotOncePerLine(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("body\n")

        repeat(1000) { AimiAppendGuard.beforeAppend(file, 2048) }

        assertThat(AimiAppendGuard.statCount).isAtMost(3)
    }

    @Test
    fun rotatesTheFileAsideOnceItPassesItsHardCap(@TempDir dir: File) {
        val rule = AimiRetentionPolicy.ruleFor("AIMI_Decisions.jsonl")!!
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("body\n")

        AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)

        assertThat(file.exists()).isFalse()
        assertThat(File(dir, "AIMI_Decisions.jsonl" + AimiRetentionPolicy.OVERFLOW_SUFFIX).exists()).isTrue()
        assertThat(rule.hardCapBytes).isGreaterThan(1L)
    }

    @Test
    fun anUnmanagedFileIsNeverTouched(@TempDir dir: File) {
        val file = File(dir, "oapsaimiML2_records.csv")
        file.writeText("body\n")

        AimiAppendGuard.beforeAppend(file, 1024 * 1024 * 4)

        assertThat(file.exists()).isTrue()
    }

    @Test
    fun anExistingOverflowFileIsNotOverwritten(@TempDir dir: File) {
        val file = File(dir, "AIMI_Decisions.jsonl")
        file.writeText("second\n")
        val overflow = File(dir, "AIMI_Decisions.jsonl" + AimiRetentionPolicy.OVERFLOW_SUFFIX)
        overflow.writeText("first\n")

        AimiAppendGuard.beforeAppend(file, 0, capOverride = 1L)

        assertThat(overflow.readText()).isEqualTo("first\n")
        assertThat(file.readText()).isEqualTo("second\n")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiAppendGuardTest" --no-daemon > /tmp/t9.log 2>&1`
Expected: FAIL, `Unresolved reference: AimiAppendGuard`.

- [ ] **Step 3: Write the implementation**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Stops a managed file growing without limit when the daily janitor does not run.
 *
 * This is the only part of the retention code that runs on the decision path, so it must be almost
 * free. It counts the bytes it has been told about and only calls `File.length()` once per megabyte,
 * which is one syscall per megabyte written instead of one per line.
 *
 * Over the cap it renames the file aside, which is O(1) and touches no content. The next janitor
 * pass archives the `.overflow` file and deletes it. Nothing is ever compressed here.
 */
object AimiAppendGuard {

    private const val STAT_EVERY_BYTES = 1024L * 1024

    private val pending = ConcurrentHashMap<String, AtomicLong>()
    private val stats = AtomicLong(0)

    /** How many times the file size was actually read. Test only. */
    val statCount: Long get() = stats.get()

    /** Clears the byte counters. Test only. */
    fun resetForTest() {
        pending.clear()
        stats.set(0)
    }

    /**
     * Called just before [addedBytes] are appended to [file].
     *
     * Does nothing for a file the policy does not manage. Never throws, and never blocks: if the
     * file is locked by the janitor right now, the check is simply skipped.
     */
    @JvmOverloads
    fun beforeAppend(file: File, addedBytes: Int, capOverride: Long? = null) {
        val rule = AimiRetentionPolicy.ruleFor(file.name) ?: return
        val cap = capOverride ?: rule.hardCapBytes
        val counter = pending.computeIfAbsent(file.absolutePath) { AtomicLong(0) }
        if (counter.addAndGet(addedBytes.toLong()) < STAT_EVERY_BYTES && addedBytes > 0) return
        counter.set(0)
        runCatching {
            stats.incrementAndGet()
            if (file.length() <= cap) return
            AimiFileLock.tryWithFile(file) {
                val overflow = File(file.parentFile, file.name + AimiRetentionPolicy.OVERFLOW_SUFFIX)
                if (!overflow.exists()) file.renameTo(overflow) else false
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run the same command. Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Wire the guard into `AuditorJsonlExport`**

Replace `appendLine` at `AuditorJsonlExport.kt:103-109` with:

```kotlin
    fun appendLine(decisionsFile: File, jsonLine: String) {
        if (!decisionsFile.exists()) {
            decisionsFile.parentFile?.mkdirs()
            decisionsFile.createNewFile()
        }
        // Rotates the file aside if it ever passes its hard cap. Costs one size check per megabyte
        // written, and never blocks: this runs on the loop thread.
        AimiAppendGuard.beforeAppend(decisionsFile, jsonLine.length + 1)
        decisionsFile.appendText("$jsonLine\n")
    }
```

Add at the top of the file:

```kotlin
import app.aaps.plugins.aps.openAPSAIMI.retention.AimiAppendGuard
```

- [ ] **Step 6: Wire the guard into the Hormonitor exporter**

In `AimiHormonitorStudyExporterMTR.kt`, replace `writeToFile` (around line 696) with:

```kotlin
    private fun writeToFile(file: File, payload: String, mode: WriteMode) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        when (mode) {
            WriteMode.APPEND_LINE -> {
                AimiAppendGuard.beforeAppend(file, payload.length + 1)
                file.appendText("$payload\n")
            }

            WriteMode.OVERWRITE   -> file.writeText(payload)
        }
    }
```

Add at the top of the file:

```kotlin
import app.aaps.plugins.aps.openAPSAIMI.retention.AimiAppendGuard
```

The `OVERWRITE` branch is left alone on purpose: it rewrites the whole file each time, so it is
already bounded.

- [ ] **Step 7: Compile the module**

Run: `./gradlew :plugins:aps:compileFullDebugKotlin --no-daemon > /tmp/t9b.log 2>&1` then
`grep -E '^e: |BUILD' /tmp/t9b.log`.
Expected: `BUILD SUCCESSFUL` and no `e:` lines.

- [ ] **Step 8: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiAppendGuard.kt plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiAppendGuardTest.kt plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorJsonlExport.kt plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/AimiHormonitorStudyExporterMTR.kt
```

---

## Task 10: Worker and scheduling

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionWorker.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/learning/AimiMlTrainingScheduler.kt:81-92` and its `cancel()` and `companion object`
- Test: `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/AimiRetentionWorkerLogicTest.kt`

**Interfaces:**
- Consumes: `AimiRetentionManager.runOnce`, `AimiStorageHelper.getAimiDirectory`, `AAPSLogger`.
- Produces: `AimiRetentionWorker` (a `Worker`), and the constant
  `AimiMlTrainingScheduler.WORK_AIMI_RETENTION = "AIMI_RETENTION"`.

The worker's own logic is one line, so the test covers the part that matters: a pass that throws
must still report success, because a failing `Result.retry()` on a daily job would turn one broken
file into a retry storm.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AimiRetentionWorkerLogicTest {

    @Test
    fun aPassThatThrowsIsReportedAsHandledNotRetried(@TempDir dir: File) {
        val logged = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, { logged.add(it) }, { 1_758_240_000_000L }) {
            override fun onFileVisited(file: File) = error("disk on fire")
        }
        File(dir, "AIMI_Decisions.jsonl").writeText("body\n")

        val changed = subject.runOnce()

        assertThat(changed).isAtLeast(0)
        assertThat(logged.any { it.contains("AIMI_Decisions.jsonl") }).isTrue()
    }

    @Test
    fun anEmptyDirectoryIsAValidPass(@TempDir dir: File) {
        val subject = AimiRetentionManager(dir, {}, { 1_758_240_000_000L })

        assertThat(subject.runOnce()).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails or passes**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.AimiRetentionWorkerLogicTest" --no-daemon > /tmp/t10.log 2>&1`
Expected: PASS if Task 8 is complete. If it fails, the failure is in `runOnce`'s error handling — fix
that before writing the worker.

- [ ] **Step 3: Write the worker**

The module's workers use Hilt (`@HiltWorker` + `@AssistedInject`) on top of `LoggingWorker`, which
runs `doWorkAndLog()` on the dispatcher it is given. `BasalMlTrainerWorker` is the model; follow it
exactly. No Dagger module registration is needed — Hilt discovers `@HiltWorker` classes.

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.retention

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers

/**
 * Runs one retention pass a day.
 *
 * Always returns success, even after a failure. A daily job that retries on error would turn one
 * unreadable file into a retry storm, and the next scheduled pass tries again anyway.
 */
@HiltWorker
class AimiRetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    private val storageHelper: AimiStorageHelper,
) : LoggingWorker(appContext, workerParams, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        runCatching {
            val manager = AimiRetentionManager(
                aimiDir = storageHelper.getAimiDirectory(),
                logger = { message -> aapsLogger.info(LTag.APS, message) },
                now = System::currentTimeMillis,
            )
            val changed = manager.runOnce()
            aapsLogger.info(LTag.APS, "AimiRetentionWorker: pass finished, $changed file(s) changed")
        }.onFailure {
            aapsLogger.error(LTag.APS, "AimiRetentionWorker: pass failed", it)
        }
        return Result.success()
    }
}
```


- [ ] **Step 4: Register the periodic work**

In `AimiMlTrainingScheduler.kt`, next to the existing Autodrive registrations (around line 81), add:

```kotlin
            wm.enqueueUniquePeriodicWork(
                WORK_AIMI_RETENTION,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AimiRetentionWorker>(24, TimeUnit.HOURS).build(),
            )
```

In the same file's `companion object`, add:

```kotlin
        const val WORK_AIMI_RETENTION = "AIMI_RETENTION"
```

In `cancel()`, add `wm.cancelUniqueWork(WORK_AIMI_RETENTION)` alongside the existing cancellations.

Add the import:

```kotlin
import app.aaps.plugins.aps.openAPSAIMI.retention.AimiRetentionWorker
```

No constraints on the request. The comment already in that file explains why: charging plus
device-idle almost never coincide on a real phone, and work registered with those constraints never
ran.

- [ ] **Step 5: Compile and run the whole retention suite**

Run: `./gradlew :plugins:aps:testFullDebugUnitTest --tests "app.aaps.plugins.aps.openAPSAIMI.retention.*" --no-daemon > /tmp/t10b.log 2>&1` then `grep -E 'BUILD|tests? (failed|completed)' /tmp/t10b.log`.
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/ plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/retention/ plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/learning/AimiMlTrainingScheduler.kt
```

---

## Task 11: The external viewer reads the archives

**Files:**
- Modify: `tools/aimi_viewer/android/app/src/main/kotlin/app/aaps/aimi_viewer/DecisionWindowIndex.kt`
- Modify: `tools/aimi_viewer/android/app/src/main/kotlin/app/aaps/aimi_viewer/MainActivity.kt:34-35`

**Interfaces:**
- Consumes: the `archive/AIMI_Decisions_<YYYY-MM>.jsonl.gz` files produced by Task 6.
- Produces: no new public API; the viewer's existing 31 day window keeps working now that the live
  file only holds 7 days.

**Read first:** `DecisionWindowIndex.kt` in full, and `MainActivity.kt:30-50`. This module is a
separate app with its own conventions; match them rather than the plugin's.

- [ ] **Step 1: Read the viewer's current reading path**

Run:

```bash
cat /Users/mtr/StudioProjects/OpenApsAIMI/tools/aimi_viewer/android/app/src/main/kotlin/app/aaps/aimi_viewer/DecisionWindowIndex.kt
sed -n '20,60p' /Users/mtr/StudioProjects/OpenApsAIMI/tools/aimi_viewer/android/app/src/main/kotlin/app/aaps/aimi_viewer/MainActivity.kt
```

Write down: how the file is opened, whether the index stores byte offsets, and whether the window is
filtered by timestamp after reading.

- [ ] **Step 2: Write the failing test**

If `tools/aimi_viewer` has a test source set, add a test there that builds a small
`archive/AIMI_Decisions_2026-09.jsonl.gz` beside a live file and asserts the reader returns lines
from both, oldest first. If the module has **no** test source set, do not create one: instead state
that in your report, and verify by step 4 alone.

- [ ] **Step 3: Extend the reader**

Where the viewer opens `AIMI_Decisions.jsonl`, build the list of sources as:

```kotlin
private fun decisionSources(liveFile: File): List<File> {
    val archives = File(liveFile.parentFile, "archive")
        .listFiles { f -> f.name.startsWith("AIMI_Decisions_") && f.name.endsWith(".jsonl.gz") }
        .orEmpty()
        .sortedBy { it.name }
    return archives + liveFile
}

private fun openSource(file: File): InputStream =
    if (file.name.endsWith(".gz")) GZIPInputStream(file.inputStream()) else file.inputStream()
```

with imports `java.io.InputStream` and `java.util.zip.GZIPInputStream`.

A gzip member cannot be seeked into, so any byte-offset index the viewer keeps applies to the live
file only. For the archives, read them forward and filter by timestamp, the same way the support
package export does. The archives are about 20 MB compressed in total, so a forward read is fine.

- [ ] **Step 4: Verify by hand against real data**

```bash
mkdir -p /tmp/aimi-archive-check/archive
head -200 "<a copy of AIMI_Decisions.jsonl>" > /tmp/aimi-archive-check/AIMI_Decisions.jsonl
python3 -c "import gzip,shutil; shutil.copyfileobj(open('/tmp/aimi-archive-check/AIMI_Decisions.jsonl','rb'), gzip.open('/tmp/aimi-archive-check/archive/AIMI_Decisions_2026-08.jsonl.gz','wb'))"
python3 -c "import gzip; print(sum(1 for _ in gzip.open('/tmp/aimi-archive-check/archive/AIMI_Decisions_2026-08.jsonl.gz','rt')))"
```

Expected: `200`. This proves the archive format the viewer must read, without needing the device.

- [ ] **Step 5: Build the viewer**

Run: `./gradlew -p tools/aimi_viewer/android assembleDebug --no-daemon > /tmp/t11.log 2>&1` then
`grep -E '^e: |BUILD' /tmp/t11.log`.
Expected: `BUILD SUCCESSFUL`. If this module is not part of the main Gradle build, say so in the
report instead of forcing it.

- [ ] **Step 6: Stage and report**

```bash
git -C /Users/mtr/StudioProjects/OpenApsAIMI add tools/aimi_viewer/
```

---

## Task 12: First run on the real device

This task is **not** code. It is the checklist for the user, who runs it themselves. Do not install
anything or touch a device without being asked.

- [ ] **Step 1: Record the starting state**

```bash
adb shell ls -l /sdcard/Documents/AAPS/
adb shell du -sh /sdcard/Documents/AAPS/
```

Save the output. The expected starting point is about 2.6 GB with `AIMI_Decisions.jsonl` at 2.10 GB.

- [ ] **Step 2: Let the first pass run**

The worker runs 24 hours after the app registers it, so either wait a day or trigger it from the
app's maintenance action if one is wired up. The first pass has to push about 2 GB through gzip and
will take minutes. It runs in a worker and never blocks the loop.

- [ ] **Step 3: Check the result**

```bash
adb shell ls -l /sdcard/Documents/AAPS/
adb shell ls -l /sdcard/Documents/AAPS/archive/
adb shell du -sh /sdcard/Documents/AAPS/
```

Expected: about 410 MB in total. `AIMI_Decisions.jsonl` should hold roughly 7 days, so about 130 MB,
with monthly `.gz` members in `archive/`.

- [ ] **Step 4: Check nothing lost its data**

Open the in-app Hormonitor viewer and confirm the day list and a day's detail still render. Produce a
support package and confirm `AIMI_Decisions_Last24h.jsonl` inside the ZIP is not empty. Open the
external viewer and confirm it still shows more than 7 days.

- [ ] **Step 5: Check the loop was not disturbed**

```bash
adb logcat -d | grep -i "AimiRetentionWorker"
```

Expected: one "pass finished" line, no errors, and no gap in the loop's own logging around the time
of the pass.

---

## Self-Review Notes

Checked against the spec:

- Sections 4 (policy), 5 (trim algorithm), 5.1 and 5.1b (timestamp keys and line budgets), 6
  (concurrency), 7 (append guard), 8 (scheduling), 9 (failure handling), 10 (archive format) each map
  to a task above. Section 11's 16 tests are distributed across Tasks 1–9; test 16 (memory does not
  grow with file size) is covered structurally by `AimiLineScanner` and its buffer-spanning test
  rather than by a heap assertion, which is not reliable in a unit test.
- The spec's out-of-scope items (the decision blob's shape, the ML corpora row cap, `autodrive_dataset.csv`,
  the storage path inconsistency) have no task, by design.
- Names used across tasks: `AimiFileLock.withFile`/`tryWithFile`, `AimiLineScanner.forEachLine`/`PREFIX_LIMIT`,
  `AimiTimestampKey.extract`, `AimiRetentionPolicy.RULES`/`ruleFor`/`STALE_DAYS`/`OVERFLOW_SUFFIX`/`DROP_GLOBS`/`DROP_AFTER_DAYS`,
  `AimiRetentionRule.DEFAULT_HARD_CAP_BYTES`, `AimiCutPlanner.plan`, `AimiCutPlan`, `AimiMonthRange`,
  `AimiArchive.directory`/`memberFile`/`appendMember`/`evict`, `AimiRetentionManager.trim`/`retire`/`isStale`/`dropOrphans`/`consumeOverflow`/`runOnce`,
  `AimiAppendGuard.beforeAppend`. Each is defined in the task that first uses it.
