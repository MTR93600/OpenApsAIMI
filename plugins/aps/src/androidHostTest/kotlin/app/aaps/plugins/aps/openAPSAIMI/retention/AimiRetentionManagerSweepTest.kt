package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

class AimiRetentionManagerSweepTest {

    private val now = 1_758_240_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun manager(dir: File) = AimiRetentionManager(dir, {}, { now })

    private fun readBack(file: File) =
        GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun monthOfNow(): String =
        DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))

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
        // A realistic recent timestamp, not epoch 1: since I1, retire() names its archive member(s)
        // after the CONTENT's own month, not the run month, so an ancient placeholder timestamp
        // would fall outside this rule's 12-month archiveMonths window and be evicted again right
        // after being written - a false failure in the test, not a production bug.
        overflow.writeText("""{"timestamp":${now - day}}""" + "\n")

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

    // --- retire() archive-rollback: carry-over ruling from Task 7's review ---
    //
    // retire() must not reproduce the truncated-gzip-member defect trim() already had fixed: a
    // failed append partway through must leave the archive target exactly as it was before this
    // pass, and must never delete the source file it could not safely archive.

    @Test
    fun aFailedAppendInsideRetireRestoresThePreviousMemberLengthAndKeepsTheSource(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")

        // First pass: a real, successful retire. This creates non-trivial pre-pass content in the
        // month's archive member, the same way an earlier day's pass would have.
        val firstPass = manager(dir).retire(file, rule)
        assertThat(firstPass).isTrue()
        val member = AimiArchive.directory(dir).listFiles()!!.single()
        val previousBytes = member.readBytes()
        val previousText = readBack(member)

        // A new source file with the same name shows up again (e.g. a stale file reappearing), and
        // this time the append into the very same month's target fails partway through.
        val second = File(dir, "dead.csv")
        second.writeText("a,b\n2,2\n")
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
                target.appendBytes(byteArrayOf(0))
                throw IOException("disk full mid member")
            }
        }

        val done = subject.retire(second, rule)

        assertThat(done).isFalse()
        assertThat(second.exists()).isTrue()
        assertThat(second.readText()).isEqualTo("a,b\n2,2\n")
        assertThat(member.readBytes()).isEqualTo(previousBytes)
        assertThat(readBack(member)).isEqualTo(previousText)
    }

    @Test
    fun aFailedAppendInsideRetireDeletesABrandNewArchiveMemberAndKeepsTheSource(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
                target.parentFile?.mkdirs()
                target.appendBytes(byteArrayOf(0))
                throw IOException("disk full mid member")
            }
        }

        val done = subject.retire(file, rule)

        assertThat(done).isFalse()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo("a,b\n1,1\n")
        assertThat(AimiArchive.memberFile(dir, "dead.csv", monthOfNow()).exists()).isFalse()
    }

    @Test
    fun aFailedDeleteAfterAGoodAppendRollsBackTheArchiveAndKeepsTheSource(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")
        // The append succeeds (a real, correct member is written), but the delete that follows fails.
        // The deleteSource seam simulates that without any filesystem permission games, which would
        // not fail as root (e.g. in a root CI container). retire() must treat a failed delete the
        // same as a failed append: roll the archive member back and keep the source.
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun deleteSource(file: File): Boolean = false
        }

        val done = subject.retire(file, rule)

        assertThat(done).isFalse()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo("a,b\n1,1\n")
        assertThat(AimiArchive.memberFile(dir, "dead.csv", monthOfNow()).exists()).isFalse()
    }

    // --- retire() safety envelope: fix round 1 (I2, I3) ---
    //
    // retire() must have the same guards trim() already has around its destructive step: a
    // free-space check before the (possibly slow) gzip pass, and an identity/growth re-check under
    // the lock before the source is deleted.

    @Test
    fun retireRefusesWhenFreeSpaceIsBelowTheEstimatePlusMargin(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun freeSpaceOf(file: File): Long = 1L
        }

        val done = subject.retire(file, rule)

        assertThat(done).isFalse()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo("a,b\n1,1\n")
        assertThat(AimiArchive.directory(dir).listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun retireAbortsAndDeletesNothingWhenTheFileIdentityChangedDuringThePass(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")
        // Archive the real (original) content first, exactly like the real append would, then
        // replace the file at the same path with different bytes of the EXACT SAME length. Same
        // length means the separate `file.length() > markedEof` growth check in retire() cannot
        // catch this on its own - only the file-key half of identityChanged can, so this isolates
        // that check specifically (unlike the growth test below, which is deliberately longer).
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
                AimiArchive.appendMember(source, start, endExclusive, target, header)
                file.renameTo(File(dir, "dead.csv.rotated"))
                file.writeText("z,y\n9,9\n")
            }
        }

        val done = subject.retire(file, rule)

        assertThat(done).isFalse()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo("z,y\n9,9\n")
        assertThat(AimiArchive.memberFile(dir, "dead.csv", monthOfNow()).exists()).isFalse()
    }

    @Test
    fun retireAbortsAndDeletesNothingWhenTheFileGrewDuringThePass(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n")
        // Archive the real (original) content first, then append more to the SAME file (same
        // identity, just longer). retire() must still refuse to delete it: the new tail was never
        // archived.
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
                AimiArchive.appendMember(source, start, endExclusive, target, header)
                file.appendText("a,b\n2,2\n")
            }
        }

        val done = subject.retire(file, rule)

        assertThat(done).isFalse()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).isEqualTo("a,b\n1,1\na,b\n2,2\n")
        assertThat(AimiArchive.memberFile(dir, "dead.csv", monthOfNow()).exists()).isFalse()
    }

    @Test
    fun retireOnAMissingFileReturnsFalseAndDeletesNothing(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)

        val done = manager(dir).retire(File(dir, "absent.csv"), rule)

        assertThat(done).isFalse()
        assertThat(AimiArchive.directory(dir).exists()).isFalse()
    }

    @Test
    fun retireOnAZeroLengthFileDeletesItWithoutArchiving(@TempDir dir: File) {
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("")

        val done = manager(dir).retire(file, rule)

        assertThat(done).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(AimiArchive.directory(dir).exists()).isFalse()
    }

    @Test
    fun runOnceReturnsTheNumberOfFilesChanged(@TempDir dir: File) {
        // Only bg.csv (a RETIRE rule) exists; every other managed file name is absent, so trim()/
        // retire() return false for them without error, and dropOrphans() finds no backup_* files.
        // The count must be exactly 1, not just non-zero.
        File(dir, "bg.csv").writeText("a,b\n1,1\n")

        val changed = manager(dir).runOnce()

        assertThat(changed).isEqualTo(1)
    }

    // --- I1: retire() splits its output by month for files with a usable timestamp key ---

    @Test
    fun retireOfAFileSpanningTwoMonthsProducesTwoArchiveMembersWithTheRightNamesAndContents(@TempDir dir: File) {
        val rule = AimiRetentionRule(
            fileName = "x.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
        )
        val zone = ZoneId.systemDefault()
        val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")
        fun monthTs(monthsAgo: Long, day: Int): Long =
            YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).minusMonths(monthsAgo)
                .atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

        val earlierTs = monthTs(1, 15)
        val laterTs = monthTs(0, 10)
        val earlierLabel = monthFormat.format(Instant.ofEpochMilli(earlierTs).atZone(zone))
        val laterLabel = monthFormat.format(Instant.ofEpochMilli(laterTs).atZone(zone))
        val earlierLine = """{"timestamp":$earlierTs}"""
        val laterLine = """{"timestamp":$laterTs}"""
        val file = File(dir, "x.jsonl")
        file.writeText("$earlierLine\n$laterLine\n")

        val done = manager(dir).retire(file, rule)

        assertThat(done).isTrue()
        assertThat(file.exists()).isFalse()
        val earlierMember = AimiArchive.memberFile(dir, "x.jsonl", earlierLabel)
        val laterMember = AimiArchive.memberFile(dir, "x.jsonl", laterLabel)
        assertThat(readBack(earlierMember)).isEqualTo("$earlierLine\n")
        assertThat(readBack(laterMember)).isEqualTo("$laterLine\n")
    }

    @Test
    fun retireArchivesATrailingLineWithNoClosingNewline(@TempDir dir: File) {
        // G4: AimiLineScanner deliberately never reports a trailing line with no closing newline (a
        // write caught mid-line). trim() leaves that line on the live side of the cut, but retire()
        // deletes the source outright - its own KDoc promises the whole file is archived first. Before
        // the fix, the trailing line's bytes were simply never in any range, so deleteSource destroyed
        // them.
        val rule = AimiRetentionRule(
            fileName = "x.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
        )
        val ts = now - day
        val monthLabel = DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
        val completeLine = """{"timestamp":$ts}"""
        val trailingLine = """{"timestamp":$ts}""" // deliberately not newline-terminated
        val file = File(dir, "x.jsonl")
        val originalContent = "$completeLine\n$trailingLine"
        file.writeText(originalContent)

        val done = manager(dir).retire(file, rule)

        assertThat(done).isTrue()
        assertThat(file.exists()).isFalse()
        val member = AimiArchive.memberFile(dir, "x.jsonl", monthLabel)
        assertThat(readBack(member)).isEqualTo(originalContent)
    }

    @Test
    fun retireRefusesToCombineHasHeaderWithATimestampKeyRatherThanSilentlyLosingTheHeader(@TempDir dir: File) {
        // G4: no rule in AimiRetentionPolicy combines these today. This guards against one being
        // added silently later - the month-split path skips the header line when planning ranges,
        // and retire() always passes header = null, so combining both would drop the header row
        // without a trace instead of failing loudly.
        val rule = AimiRetentionRule(
            fileName = "x.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
            hasHeader = true,
        )
        val file = File(dir, "x.jsonl")
        file.writeText("h\n" + """{"timestamp":${now - day}}""" + "\n")
        val warned = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, {}, { now }, warn = { warned.add(it) }) {}

        val done = subject.retire(file, rule)

        assertThat(done).isFalse()
        assertThat(file.exists()).isTrue()
        assertThat(warned).hasSize(1)
        assertThat(warned.single()).contains("hasHeader")
    }

    @Test
    fun retireGivesASaneMonthLabelWhenTheFirstLinesTimestampCannotBeRead(@TempDir dir: File) {
        // G5: a blank or unparseable first line makes AimiTimestampKey.extract return null for it,
        // so the planner falls back to labeling it with its seeded "now". retire() used to pass
        // Long.MAX_VALUE as that "now", which formats as "+292278994-08" - a label matching neither
        // AimiArchive.evict's regex (never reclaimed) nor the viewer's pattern (invisible).
        val rule = AimiRetentionRule(
            fileName = "x.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
        )
        val file = File(dir, "x.jsonl")
        file.writeText("not json, no timestamp field at all\n" + """{"timestamp":${now - day}}""" + "\n")

        val done = manager(dir).retire(file, rule)

        assertThat(done).isTrue()
        val archived = AimiArchive.directory(dir).listFiles().orEmpty()
        assertThat(archived).isNotEmpty()
        val expectedMonth = monthOfNow()
        archived.forEach { member ->
            assertThat(member.name).isEqualTo("x_${expectedMonth}.jsonl.gz")
        }
    }

    @Test
    fun retireOfAFileWithNoTimestampKeyKeepsTheSingleMemberBehaviour(@TempDir dir: File) {
        // Dead RETIRE files (like bg.csv, oapsaimi_records.csv) carry no timestampKey and are not
        // read by the external viewer, so a single member named for the run month is still correct.
        val rule = AimiRetentionRule("dead.csv", AimiRetentionOp.RETIRE, archiveMonths = 12, hasHeader = true)
        val file = File(dir, "dead.csv")
        file.writeText("a,b\n1,1\n2,2\n")

        val done = manager(dir).retire(file, rule)

        assertThat(done).isTrue()
        assertThat(AimiArchive.directory(dir).listFiles()).hasLength(1)
        assertThat(AimiArchive.memberFile(dir, "dead.csv", monthOfNow()).exists()).isTrue()
    }

    // --- C2: interrupted-archive recovery, at the start of every pass ---

    @Test
    fun recoverInterruptedArchivesRestoresATruncatedMemberToItsPreCrashLengthAndDeletesTheMarker(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\nthree\n")
        val target = AimiArchive.memberFile(dir, "x.jsonl", "2026-09")

        // A real, complete append first - this is the "good" content an earlier pass produced.
        AimiArchive.appendMember(source, 0L, 4L, target, null)
        val goodBytes = target.readBytes()

        // Simulate a crash partway through a SECOND append: the marker survives (it records the
        // length from before this append started), but the bytes appended after it never got a
        // valid gzip trailer.
        val marker = File(target.parentFile, target.name + ".partial")
        marker.writeText(goodBytes.size.toString())
        target.appendBytes(byteArrayOf(1, 2, 3))

        val recovered = manager(dir).recoverInterruptedArchives()

        assertThat(recovered).isEqualTo(1)
        assertThat(target.readBytes()).isEqualTo(goodBytes)
        assertThat(marker.exists()).isFalse()
    }

    @Test
    fun recoverInterruptedArchivesDeletesABrandNewMemberInterruptedBeforeItHadAnyPriorContent(@TempDir dir: File) {
        val target = AimiArchive.memberFile(dir, "x.jsonl", "2026-09")
        target.parentFile.mkdirs()
        // A crash on the very first append to this target: it did not exist before this pass, so
        // the marker records length 0, and recovery must delete it outright, not truncate to 0
        // (a zero-length .gz would break the next pass's GZIPInputStream, same as trim()'s rollback).
        target.writeBytes(byteArrayOf(1, 2, 3))
        File(target.parentFile, target.name + ".partial").writeText("0")

        val recovered = manager(dir).recoverInterruptedArchives()

        assertThat(recovered).isEqualTo(1)
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun recoverInterruptedArchivesDoesNotZeroExtendATargetAnEarlierRollbackAlreadyShrankBelowTheMarker(@TempDir dir: File) {
        // G1: same target, twice, in one pass - a real earlier day's content (P bytes), then a
        // second append within a later pass that grows it further (to L1) before something else in
        // that same pass fails and rolls the target back to P. If that second append's OWN marker
        // (recording L1, its start length) survives - a genuine crash between its own write and its
        // own cleanup can still do that, even with G1's fix - a naive recovery would trust the stale
        // L1 against a target now at P < L1, and RandomAccessFile.setLength EXTENDS a file shorter
        // than the requested length, zero-padding it and making every member after it unreadable.
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\n")
        val target = AimiArchive.memberFile(dir, "x.jsonl", "2026-09")

        // P: real content from an earlier, successful pass.
        AimiArchive.appendMember(source, 0L, 4L, target, null)
        val goodBytesAtP = target.readBytes()

        // L1: a further, successful append within THIS pass (grows the target past P).
        AimiArchive.appendMember(source, 4L, 8L, target, null)
        val lengthAtL1 = target.length()
        assertThat(lengthAtL1).isGreaterThan(goodBytesAtP.size.toLong())

        // This pass's OWN rollback (a different, later failure in the same pass, already covered by
        // AimiRetentionManagerTrimTest's "aSecondAppendToTheSameMonthTarget..." test) correctly
        // restores the target to P. The second append's marker - recording L1 - is what survives.
        target.writeBytes(goodBytesAtP)
        val marker = File(target.parentFile, target.name + ".partial")
        marker.writeText(lengthAtL1.toString())

        val recovered = manager(dir).recoverInterruptedArchives()

        assertThat(recovered).isEqualTo(1)
        assertThat(target.readBytes()).isEqualTo(goodBytesAtP)
        assertThat(readBack(target)).isEqualTo("one\n")
        assertThat(marker.exists()).isFalse()
    }

    @Test
    fun recoverInterruptedArchivesDoesNotFabricateATargetThatNoLongerExists(@TempDir dir: File) {
        // G2: the target this marker was written for was deleted out of band (or never fully
        // created) while the marker survived. previousLength alone ("5") looks like the target
        // existed, but RandomAccessFile(target, "rw") would CREATE it and zero-pad it out to 5 bytes
        // - a fabricated ".gz" that GZIPInputStream rejects outright, worse than no file at all.
        val target = AimiArchive.memberFile(dir, "x.jsonl", "2026-09")
        target.parentFile.mkdirs()
        val marker = File(target.parentFile, target.name + ".partial")
        marker.writeText("5")

        val recovered = manager(dir).recoverInterruptedArchives()

        assertThat(recovered).isEqualTo(1)
        assertThat(target.exists()).isFalse()
        assertThat(marker.exists()).isFalse()
    }

    @Test
    fun recoverInterruptedArchivesIsANoOpWhenNothingWasInterrupted(@TempDir dir: File) {
        assertThat(manager(dir).recoverInterruptedArchives()).isEqualTo(0)
    }

    @Test
    fun runOnceRecoversInterruptedArchivesBeforeProcessingAnyManagedFile(@TempDir dir: File) {
        val target = AimiArchive.memberFile(dir, "AIMI_Decisions.jsonl", "2026-09")
        target.parentFile.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        File(target.parentFile, target.name + ".partial").writeText("0")

        manager(dir).runOnce()

        assertThat(target.exists()).isFalse()
        assertThat(File(target.parentFile, target.name + ".partial").exists()).isFalse()
    }
}
