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

    // --- I2: failures and skips are logged through `warn`, not the plain `logger` ---

    @Test
    fun aFileWhereNoLineHasTheKeyWarnsNamingTheFileAndTheKey(@TempDir dir: File) {
        // The blackbox file uses "wall_ms", not "timestamp"; a rule that (wrongly) declares
        // "timestamp" for a file that only ever carries "wall_ms" must not be silently untrimmed.
        val file = File(dir, "x.jsonl")
        file.writeText("{\"wall_ms\":1}\n{\"wall_ms\":2}\n")
        val warned = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, { logged.add(it) }, { now }, warn = { warned.add(it) }) {}

        val rewritten = subject.trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(warned).hasSize(1)
        assertThat(warned.single()).contains("x.jsonl")
        assertThat(warned.single()).contains("timestamp")
        assertThat(logged).isEmpty()
    }

    @Test
    fun theCommonCaseOfNothingOutOfWindowDoesNotWarn(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        file.writeText(line(now - day) + "\n" + line(now) + "\n")
        val warned = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, {}, { now }, warn = { warned.add(it) }) {}

        subject.trim(file, jsonlRule())

        assertThat(warned).isEmpty()
    }

    @Test
    fun notEnoughFreeSpaceWarnsNamingTheFileAndTheByteCounts(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        file.writeText(line(now - 30 * day) + "\n" + line(now) + "\n")
        val warned = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, {}, { now }, warn = { warned.add(it) }) {
            override fun freeSpaceOf(file: File): Long = 1L
        }

        val rewritten = subject.trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(warned).hasSize(1)
        assertThat(warned.single()).contains("x.jsonl")
        assertThat(warned.single()).contains("needed")
    }

    // --- M3: a header line larger than the cap is skipped, not read whole into memory ---

    @Test
    fun aHeaderLargerThanTheCapSkipsTheTrimAndWarnsInsteadOfAllocatingItWhole(@TempDir dir: File) {
        val rule = AimiRetentionRule(
            fileName = "x.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 1,
            archiveMonths = 3,
            hasHeader = true,
        )
        val file = File(dir, "x.csv")
        val hugeHeader = "h".repeat(64 * 1024 + 1) + "\n"
        val before = hugeHeader + "1,1\n2,2\n"
        file.writeText(before)
        val warned = mutableListOf<String>()
        val subject = object : AimiRetentionManager(dir, {}, { now }, warn = { warned.add(it) }) {}

        val rewritten = subject.trim(file, rule)

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(before)
        assertThat(AimiArchive.directory(dir).exists()).isFalse()
        assertThat(warned).hasSize(1)
        assertThat(warned.single()).contains("x.csv")
        assertThat(warned.single()).contains("header")
    }

    @Test
    fun aMissingFileIsNotAnError(@TempDir dir: File) {
        val rewritten = manager(dir).trim(File(dir, "absent.jsonl"), jsonlRule())

        assertThat(rewritten).isFalse()
    }

    @Test
    fun aFailedMoveLeavesTheLiveFileUntouchedAndRollsBackTheArchive(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val before = line(now - 30 * day) + "\n" + line(now - day) + "\n"
        file.writeText(before)

        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun moveIntoPlace(tmp: File, file: File) {
                throw IOException("boom")
            }
        }
        val rewritten = subject.trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(before)
        assertThat(File(dir, "x.jsonl.tmp").exists()).isFalse()
        assertThat(archived(dir)).isEmpty()
        // The archive target did not exist before this pass, so rollback must delete it, not leave
        // a zero-length file behind (a zero-length .gz would break the next pass's GZIPInputStream).
        val monthLabel = DateTimeFormatter.ofPattern("yyyy-MM").format(
            Instant.ofEpochMilli(now - 30 * day).atZone(ZoneId.systemDefault())
        )
        assertThat(AimiArchive.memberFile(dir, "x.jsonl", monthLabel).exists()).isFalse()
    }

    @Test
    fun theFileBeingReplacedDuringTheCopyAbortsAndLeavesTheReplacementUntouched(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        file.writeText(line(now - 30 * day) + "\n" + line(now - day) + "\n")
        val fresh = "fresh content, not related\n"

        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun onTailCopied(target: File) {
                val rotated = File(dir, "x.jsonl.overflow")
                file.renameTo(rotated)
                file.writeText(fresh)
            }
        }
        val rewritten = subject.trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(fresh)
        assertThat(File(dir, "x.jsonl.tmp").exists()).isFalse()
    }

    @Test
    fun aFailedArchiveAppendRestoresThePreviousMemberLength(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        file.writeText(line(now - 30 * day) + "\n" + line(now - day) + "\n")

        val firstPass = manager(dir).trim(file, jsonlRule())
        assertThat(firstPass).isTrue()
        val member = AimiArchive.directory(dir).listFiles()!!.single()
        val previousBytes = member.readBytes()
        val previousArchivedText = archived(dir)

        val second = """{"event_id":"e2","timestamp":${now - 30 * day}}"""
        file.writeText(second + "\n" + line(now - day) + "\n")
        val before = file.readText()
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
                target.appendBytes(byteArrayOf(0))
                throw IOException("disk full mid member")
            }
        }

        val rewritten = subject.trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(before)
        assertThat(member.readBytes()).isEqualTo(previousBytes)
        assertThat(archived(dir)).isEqualTo(previousArchivedText)
    }

    @Test
    fun theFreeSpaceCheckAccountsForArchiveOutputToo(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val old = line(now - 30 * day)
        val kept = line(now - day)
        file.writeText("$old\n$kept\n")

        val archivedBytes = ("$old\n").toByteArray(Charsets.UTF_8).size.toLong()
        val tailBytes = ("$kept\n").toByteArray(Charsets.UTF_8).size.toLong()
        val margin = 32L * 1024 * 1024
        // /8, not /4: I3 lowered the gzip-ratio margin in the production formula (gzip on this data
        // runs at roughly 10:1, so /4 was about 2.5x over).
        val freeSpace = tailBytes + margin + archivedBytes / 8 - 1

        val rewritten = manager(dir, freeSpace = freeSpace).trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo("$old\n$kept\n")
        assertThat(AimiArchive.directory(dir).exists()).isFalse()
    }

    @Test
    fun aSecondAppendToTheSameMonthTargetRollsBackToTheStateBeforeThisPassOnly(@TempDir dir: File) {
        val file = File(dir, "x.jsonl")
        val zone = ZoneId.systemDefault()
        val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")
        fun monthTs(monthsAgo: Long, day: Int): Long =
            YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).minusMonths(monthsAgo)
                .atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

        val monthATs1 = monthTs(6, 5)
        val monthATs2 = monthTs(6, 20)
        val monthBTs = monthTs(5, 10)
        val monthALabel = monthFormat.format(Instant.ofEpochMilli(monthATs1).atZone(zone))
        val targetA = AimiArchive.memberFile(dir, "x.jsonl", monthALabel)

        // Pass 1: real archiving, creates non-trivial pre-pass content for month A (and month B).
        file.writeText(line(monthATs1) + "\n" + line(monthBTs) + "\n" + line(now - day) + "\n")
        val firstPass = manager(dir).trim(file, jsonlRule())
        assertThat(firstPass).isTrue()
        assertThat(targetA.exists()).isTrue()
        val previousTargetABytes = targetA.readBytes()
        val previousArchivedText = archived(dir)

        // Pass 2: month A appears twice, split by month B, so the plan yields two non-adjacent
        // ranges for the same target file. Verify that before trusting the rest of the test.
        file.writeText(
            line(monthATs1) + "\n" + line(monthBTs) + "\n" + line(monthATs2) + "\n" + line(now - day) + "\n"
        )
        val before = file.readText()
        val plan = AimiCutPlanner.plan(file, jsonlRule(), now)
        assertThat(plan.ranges.count { it.month == monthALabel }).isEqualTo(2)

        // The second append to any target in this pass fails partway, after writing a corrupt byte.
        val callCount = mutableMapOf<String, Int>()
        val subject = object : AimiRetentionManager(dir, {}, { now }) {
            override fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
                val count = (callCount[target.absolutePath] ?: 0) + 1
                callCount[target.absolutePath] = count
                if (count == 2) {
                    target.appendBytes(byteArrayOf(0))
                    throw IOException("disk full mid member")
                }
                AimiArchive.appendMember(source, start, endExclusive, target, header)
            }
        }

        val rewritten = subject.trim(file, jsonlRule())

        assertThat(rewritten).isFalse()
        assertThat(file.readText()).isEqualTo(before)
        assertThat(targetA.readBytes()).isEqualTo(previousTargetABytes)
        assertThat(archived(dir)).isEqualTo(previousArchivedText)
    }
}
