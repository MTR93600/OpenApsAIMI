package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

class AimiArchiveTest {

    private val utc = TimeZone.UTC

    /** The archive speaks AimiPath now; these tests still build their fixtures with java.io.File. */
    private fun File.asAimiPath() = AimiPath(absolutePath)

    /** And back again, so the fixtures can still read and write the files the archive names. */
    private fun AimiPath.asFile() = File(value)

    /**
     * The archive only asks the storage about paths, text and sizes, and the real Android storage
     * answers all of that from the path alone, so the helper never gets called.
     */
    private val storage: AimiStorage = AndroidAimiStorage(mock<AimiStorageHelper>())

    private fun readBack(file: File): String =
        GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun aMemberReadsBackAsExactlyTheBytesThatWereRemoved(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\nthree\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")

        AimiArchive.appendMember(storage, source.asAimiPath(), 0L, 8L, target.asAimiPath(), null)

        assertThat(readBack(target)).isEqualTo("one\ntwo\n")
    }

    @Test
    fun twoAppendsReadBackAsOneStreamInOrder(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")

        AimiArchive.appendMember(storage, source.asAimiPath(), 0L, 4L, target.asAimiPath(), null)
        AimiArchive.appendMember(storage, source.asAimiPath(), 4L, 8L, target.asAimiPath(), null)

        assertThat(readBack(target)).isEqualTo("one\ntwo\n")
    }

    @Test
    fun aHeaderIsWrittenAtTheTopOfEveryMember(@TempDir dir: File) {
        val source = File(dir, "x.csv")
        source.writeText("a,b\n1,1\n2,2\n")
        val target = File(dir, "archive/x_2026-09.csv.gz")
        val header = "a,b\n".toByteArray()

        AimiArchive.appendMember(storage, source.asAimiPath(), 4L, 8L, target.asAimiPath(), header)
        AimiArchive.appendMember(storage, source.asAimiPath(), 8L, 12L, target.asAimiPath(), header)

        assertThat(readBack(target)).isEqualTo("a,b\n1,1\na,b\n2,2\n")
    }

    // --- C2: crash-atomic append, via a `.partial` sidecar marker ---

    private fun markerOf(target: File) = File(target.parentFile, target.name + ".partial")

    @Test
    fun appendMemberLeavesNoPartialMarkerBehindOnSuccess(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")

        AimiArchive.appendMember(storage, source.asAimiPath(), 0L, 4L, target.asAimiPath(), null)

        assertThat(markerOf(target).exists()).isFalse()
    }

    @Test
    fun appendMemberLeavesNoPartialMarkerBehindAfterASecondAppendToTheSameTarget(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")

        AimiArchive.appendMember(storage, source.asAimiPath(), 0L, 4L, target.asAimiPath(), null)
        AimiArchive.appendMember(storage, source.asAimiPath(), 4L, 8L, target.asAimiPath(), null)

        assertThat(markerOf(target).exists()).isFalse()
        assertThat(readBack(target)).isEqualTo("one\ntwo\n")
    }

    // --- G1/G3 (final re-review): the marker is removed on success and survives failure so the next pass can repair ---

    @Test
    fun markerSurvivesWhenTheAppendItselfThrows(@TempDir dir: File) {
        val source = File(dir, "x.jsonl")
        source.writeText("one\ntwo\n")
        val target = File(dir, "archive/x_2026-09.jsonl.gz")
        target.parentFile.mkdirs()
        // Opening a directory as a FileOutputStream throws immediately, before a single byte of the
        // member is written - this simulates any in-process failure of the write below the marker.
        target.mkdir()

        assertThrows(IOException::class.java) {
            AimiArchive.appendMember(storage, source.asAimiPath(), 0L, 4L, target.asAimiPath(), null)
        }

        // After R3's fix, appendMember removes the marker only on success. When an append throws,
        // the marker survives intentionally so the next pass's recoverInterruptedArchives() can
        // detect and repair the incomplete or truncated member. An in-process failure like this one
        // is also handled by the caller's own rollback, but the surviving marker ensures that a
        // failure outside the caller's try/catch (a process kill or WorkManager stop) can also be
        // repaired - see AimiRetentionManagerSweepTest's G1 test for what could happen without it.
        assertThat(markerOf(target).exists()).isTrue()
    }

    @Test
    fun findInterruptedAppendsDiscoversASurvivingMarkerAndItsRecordedLength(@TempDir dir: File) {
        val archive = AimiArchive.directory(storage, dir.asAimiPath()).asFile()
        archive.mkdirs()
        // Simulates a crash partway through appending a SECOND member to an existing target: the
        // marker recorded the target's length before this (interrupted) append started.
        val target = File(archive, "x_2026-09.jsonl.gz")
        target.writeText("some bytes, possibly a truncated gzip member")
        markerOf(target).writeText("5")

        val interrupted = AimiArchive.findInterruptedAppends(storage, dir.asAimiPath())

        assertThat(interrupted).hasSize(1)
        assertThat(interrupted.single().target).isEqualTo(target.asAimiPath())
        assertThat(interrupted.single().previousLength).isEqualTo(5L)
    }

    @Test
    fun findInterruptedAppendsSkipsAMalformedMarkerRatherThanGuessAtItsLength(@TempDir dir: File) {
        val archive = AimiArchive.directory(storage, dir.asAimiPath()).asFile()
        archive.mkdirs()
        File(archive, "x_2026-09.jsonl.gz.partial").writeText("not-a-number")

        assertThat(AimiArchive.findInterruptedAppends(storage, dir.asAimiPath())).isEmpty()
    }

    @Test
    fun findInterruptedAppendsReturnsEmptyWhenTheArchiveDirectoryDoesNotExist(@TempDir dir: File) {
        assertThat(AimiArchive.findInterruptedAppends(storage, dir.asAimiPath())).isEmpty()
    }

    @Test
    fun memberFileNamesKeepTheOriginalExtension(@TempDir dir: File) {
        assertThat(AimiArchive.memberFile(storage, dir.asAimiPath(), "AIMI_Decisions.jsonl", "2026-09").asFile().name)
            .isEqualTo("AIMI_Decisions_2026-09.jsonl.gz")
        assertThat(AimiArchive.memberFile(storage, dir.asAimiPath(), "oapsaimi_wcycle.csv", "2026-08").asFile().name)
            .isEqualTo("oapsaimi_wcycle_2026-08.csv.gz")
    }

    @Test
    fun evictionRemovesOnlyMonthsOlderThanTheWindow(@TempDir dir: File) {
        val archive = AimiArchive.directory(storage, dir.asAimiPath()).asFile()
        archive.mkdirs()
        listOf("2026-04", "2026-05", "2026-08", "2026-09").forEach {
            File(archive, "x_$it.jsonl.gz").writeText("body")
        }
        val now = 1_789_776_000_000L // 2026-09-19 in UTC; keeping 3 months means 2026-06 onwards

        val removed = AimiArchive.evict(storage, dir.asAimiPath(), "x.jsonl", keepMonths = 3, nowMs = now, zone = utc)

        assertThat(removed).isEqualTo(2)
        assertThat(File(archive, "x_2026-04.jsonl.gz").exists()).isFalse()
        assertThat(File(archive, "x_2026-05.jsonl.gz").exists()).isFalse()
        assertThat(File(archive, "x_2026-08.jsonl.gz").exists()).isTrue()
        assertThat(File(archive, "x_2026-09.jsonl.gz").exists()).isTrue()
    }

    @Test
    fun evictionIgnoresMembersOfOtherFiles(@TempDir dir: File) {
        val archive = AimiArchive.directory(storage, dir.asAimiPath()).asFile()
        archive.mkdirs()
        File(archive, "x_2020-01.jsonl.gz").writeText("body")
        File(archive, "y_2020-01.jsonl.gz").writeText("body")

        AimiArchive.evict(storage, dir.asAimiPath(), "x.jsonl", keepMonths = 1, nowMs = 1_758_240_000_000L, zone = utc)

        assertThat(File(archive, "y_2020-01.jsonl.gz").exists()).isTrue()
    }
}
