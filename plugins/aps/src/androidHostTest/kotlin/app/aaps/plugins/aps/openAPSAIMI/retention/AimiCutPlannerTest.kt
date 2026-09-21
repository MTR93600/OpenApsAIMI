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
