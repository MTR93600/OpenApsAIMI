package app.aaps.plugins.aps.openAPSAIMI.comparison

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Checks that the parser reads the columns the writer really produces.
 *
 * The fixture of the current layout is built from `AimiSmbComparator.CSV_HEADER`, so this test
 * fails as soon as the writer changes its column list without the parser following.
 */
class ComparisonCsvParserFormatTest {

    private val parser = ComparisonCsvParser()

    private val headerColumns = AimiSmbComparator.CSV_HEADER.trim().split(",")

    /** Values of the current layout (schema 3), by column name. */
    private val currentValues: Map<String, String> = mapOf(
        "SchemaVersion" to AimiSmbComparator.CSV_SCHEMA_VERSION,
        "Timestamp" to "1758304800000",
        "Date" to "2026-09-19 18:40:00",
        "BG" to "142.0",
        "Delta" to "2.50",
        "ShortAvgDelta" to "1.80",
        "LongAvgDelta" to "0.90",
        "IOB" to "3.20",
        "COB" to "18.0",
        "AIMI_Rate" to "1.85",
        "AIMI_SMB" to "0.450",
        "AIMI_Duration" to "30",
        "AIMI_EventualBG" to "118.0",
        "AIMI_TargetBG" to "95.0",
        "SMB_Rate" to "0.90",
        "SMB_SMB" to "0.200",
        "SMB_Duration" to "30",
        "SMB_EventualBG" to "131.0",
        "SMB_TargetBG" to "100.0",
        "Diff_Rate" to "0.95",
        "Diff_SMB" to "0.250",
        "Diff_EventualBG" to "-13.0",
        "MaxIOB" to "8.0",
        "MaxBasal" to "6.00",
        "MicroBolus_Allowed" to "1",
        "AIMI_Insulin_30min" to "0.604",
        "SMB_Insulin_30min" to "0.275",
        "Cumul_Diff" to "1.234",
        "AIMI_Active" to "1",
        "SMB_Active" to "1",
        "Both_Active" to "1",
        "AIMI_UAM_Last" to "126.0",
        "SMB_UAM_Last" to "134.0",
        "Verdict" to "AIMI_AGGRESSIVE",
        "Artifact_Flag" to "VALID",
        "Diff_Sign" to "+",
        "AIMI_Flag_MealPriority" to "1",
        "AIMI_Flag_Refractory" to "0",
        "AIMI_Flag_Throttle" to "0",
        "AIMI_Flag_CBF" to "1",
        "SMB_Flag_Refractory" to "1",
        "SMB_Flag_Throttle" to "0",
        "SMB_Flag_CBF" to "0",
        "Context_MealRise" to "1",
        "Context_COB_Active" to "1",
        "Context_UAM_Bias" to "0",
        "SMB_LastBolusAgeMin" to "22.5",
        "Reason_AIMI" to "\"MEAL_PRIORITY_CONTEXT | rate 1.85\"",
        "Reason_SMB" to "\"SMB interval=3; lastBolusAge=22.5\""
    )

    /** Builds a row of the current layout, in the exact column order of the writer's header. */
    private fun currentRow(overrides: Map<String, String> = emptyMap()): String {
        val values = currentValues + overrides
        val missing = headerColumns.filterNot { values.containsKey(it) }
        check(missing.isEmpty()) { "The writer header changed, the fixture has no value for: $missing" }
        val unknown = values.keys.filterNot { headerColumns.contains(it) }
        check(unknown.isEmpty()) { "The fixture has values for columns the writer no longer writes: $unknown" }
        return headerColumns.joinToString(",") { values.getValue(it) }
    }

    /** Old layout with the Verdict block but without SchemaVersion (37 columns). */
    private fun legacyRowWithVerdict(): String {
        val columns = headerColumns
            .filter { it != "SchemaVersion" }
            .filterNot { it.startsWith("AIMI_Flag_") || it.startsWith("SMB_Flag_") }
            .filterNot { it.startsWith("Context_") || it == "SMB_LastBolusAgeMin" }
        check(columns.size == 37) { "Legacy fixture should have 37 columns, has ${columns.size}" }
        return columns.joinToString(",") { currentValues.getValue(it) }
    }

    /**
     * Old layout with the Verdict block (37 columns), but with `Reason_AIMI`/`Reason_SMB` set to the
     * bare text `1` instead of a real reason.
     *
     * In this 37-column row, index 35 and 36 (relative to Timestamp) are genuinely `Reason_AIMI` and
     * `Reason_SMB` — the last two columns of this layout. Those same fixed indexes are what
     * `AIMI_Flag_MealPriority` (35) and `AIMI_Flag_Refractory` (36) read on the current, 48-column
     * layout. If the `hasFlagBlock` / `FLAG_COLUMNS` gate in `ComparisonCsvParser` were removed while
     * the index arithmetic stayed, reading this row would land on the same two indexes and see the
     * literal text `1`, and misreport both flags as true. With the gate in place, both must stay
     * false, which is what [aLegacyRowWhoseReasonTextIsOneDoesNotBecomeAFlag] pins.
     */
    private fun legacyRowWithVerdictAndReasonTextOne(): String {
        val columns = headerColumns
            .filter { it != "SchemaVersion" }
            .filterNot { it.startsWith("AIMI_Flag_") || it.startsWith("SMB_Flag_") }
            .filterNot { it.startsWith("Context_") || it == "SMB_LastBolusAgeMin" }
        check(columns.size == 37) { "Legacy fixture should have 37 columns, has ${columns.size}" }
        val values = currentValues + mapOf("Reason_AIMI" to "1", "Reason_SMB" to "1")
        return columns.joinToString(",") { values.getValue(it) }
    }

    /** Oldest layout: no SchemaVersion and no Verdict block (34 columns). */
    private fun legacyRowWithoutVerdict(): String {
        val columns = headerColumns
            .filter { it != "SchemaVersion" }
            .filterNot { it.startsWith("AIMI_Flag_") || it.startsWith("SMB_Flag_") }
            .filterNot { it.startsWith("Context_") || it == "SMB_LastBolusAgeMin" }
            .filterNot { it == "Verdict" || it == "Artifact_Flag" || it == "Diff_Sign" }
        check(columns.size == 34) { "Oldest fixture should have 34 columns, has ${columns.size}" }
        return columns.joinToString(",") { currentValues.getValue(it) }
    }

    private fun write(dir: File, vararg rows: String): File {
        val file = File(dir, "comparison_aimi_smb.csv")
        file.writeText(AimiSmbComparator.CSV_HEADER + rows.joinToString("\n") + "\n")
        return file
    }

    @Test
    fun theWriterHeaderAndTheWriterRowHaveTheSameNumberOfColumns() {
        assertThat(headerColumns).hasSize(49)
        assertThat(currentRow().split(",")).hasSize(headerColumns.size)
    }

    @Test
    fun aCurrentRowIsReadWithTheColumnOrderOfTheWriter(@TempDir dir: File) {
        val entries = parser.parse(write(dir, currentRow()))

        assertThat(entries).hasSize(1)
        val entry = entries.first()
        assertThat(entry.timestamp).isEqualTo(1758304800000L)
        assertThat(entry.date).isEqualTo("2026-09-19 18:40:00")
        assertThat(entry.bg).isEqualTo(142.0)
        assertThat(entry.delta).isEqualTo(2.5)
        assertThat(entry.shortAvgDelta).isEqualTo(1.8)
        assertThat(entry.longAvgDelta).isEqualTo(0.9)
        assertThat(entry.iob).isEqualTo(3.2)
        assertThat(entry.cob).isEqualTo(18.0)
        assertThat(entry.aimiRate).isEqualTo(1.85)
        assertThat(entry.aimiSmb).isEqualTo(0.45)
        assertThat(entry.aimiDuration).isEqualTo(30)
        assertThat(entry.aimiEventualBg).isEqualTo(118.0)
        assertThat(entry.aimiTargetBg).isEqualTo(95.0)
        assertThat(entry.smbRate).isEqualTo(0.9)
        assertThat(entry.smbSmb).isEqualTo(0.2)
        assertThat(entry.smbDuration).isEqualTo(30)
        assertThat(entry.smbEventualBg).isEqualTo(131.0)
        assertThat(entry.smbTargetBg).isEqualTo(100.0)
        assertThat(entry.diffRate).isEqualTo(0.95)
        assertThat(entry.diffSmb).isEqualTo(0.25)
        assertThat(entry.diffEventualBg).isEqualTo(-13.0)
        assertThat(entry.maxIob).isEqualTo(8.0)
        assertThat(entry.maxBasal).isEqualTo(6.0)
        assertThat(entry.microBolusAllowed).isTrue()
        assertThat(entry.aimiInsulin30).isEqualTo(0.604)
        assertThat(entry.smbInsulin30).isEqualTo(0.275)
        assertThat(entry.cumulativeDiff).isEqualTo(1.234)
        assertThat(entry.aimiActive).isTrue()
        assertThat(entry.smbActive).isTrue()
        assertThat(entry.bothActive).isTrue()
        assertThat(entry.aimiUamLast).isEqualTo(126.0)
        assertThat(entry.smbUamLast).isEqualTo(134.0)
        assertThat(entry.verdict).isEqualTo("AIMI_AGGRESSIVE")
        assertThat(entry.artifactFlag).isEqualTo("VALID")
        assertThat(entry.diffSign).isEqualTo("+")
        assertThat(entry.reasonAimi).isEqualTo("MEAL_PRIORITY_CONTEXT | rate 1.85")
        assertThat(entry.reasonSmb).isEqualTo("SMB interval=3; lastBolusAge=22.5")
    }

    @Test
    fun anEmptyOptionalColumnBecomesNull(@TempDir dir: File) {
        val row = currentRow(mapOf("AIMI_UAM_Last" to "", "SMB_UAM_Last" to ""))

        val entry = parser.parse(write(dir, row)).single()

        assertThat(entry.aimiUamLast).isNull()
        assertThat(entry.smbUamLast).isNull()
        assertThat(entry.bg).isEqualTo(142.0)
    }

    @Test
    fun anOldRowWithTheVerdictBlockIsStillRead(@TempDir dir: File) {
        val entry = parser.parse(write(dir, legacyRowWithVerdict())).single()

        assertThat(entry.timestamp).isEqualTo(1758304800000L)
        assertThat(entry.date).isEqualTo("2026-09-19 18:40:00")
        assertThat(entry.bg).isEqualTo(142.0)
        assertThat(entry.smbUamLast).isEqualTo(134.0)
        assertThat(entry.verdict).isEqualTo("AIMI_AGGRESSIVE")
        assertThat(entry.artifactFlag).isEqualTo("VALID")
        assertThat(entry.diffSign).isEqualTo("+")
        assertThat(entry.reasonAimi).isEqualTo("MEAL_PRIORITY_CONTEXT | rate 1.85")
        assertThat(entry.reasonSmb).isEqualTo("SMB interval=3; lastBolusAge=22.5")
    }

    @Test
    fun theOldestRowWithoutTheVerdictBlockIsStillRead(@TempDir dir: File) {
        val entry = parser.parse(write(dir, legacyRowWithoutVerdict())).single()

        assertThat(entry.timestamp).isEqualTo(1758304800000L)
        assertThat(entry.bg).isEqualTo(142.0)
        assertThat(entry.aimiRate).isEqualTo(1.85)
        assertThat(entry.verdict).isEmpty()
        assertThat(entry.artifactFlag).isEmpty()
        assertThat(entry.diffSign).isEmpty()
        assertThat(entry.reasonAimi).isEqualTo("MEAL_PRIORITY_CONTEXT | rate 1.85")
        assertThat(entry.reasonSmb).isEqualTo("SMB interval=3; lastBolusAge=22.5")
    }

    @Test
    fun oneFileCanHoldRowsOfSeveralLayouts(@TempDir dir: File) {
        // The header is written once, when the file is created, so a file that was started with an
        // old build keeps its old header while newer rows are appended to it.
        val file = write(dir, legacyRowWithoutVerdict(), legacyRowWithVerdict(), currentRow())

        val entries = parser.parse(file)

        assertThat(entries).hasSize(3)
        assertThat(entries.map { it.bg }).containsExactly(142.0, 142.0, 142.0)
        assertThat(entries.map { it.verdict }).containsExactly("", "AIMI_AGGRESSIVE", "AIMI_AGGRESSIVE")
    }

    @Test
    fun aFileThatLostItsHeaderStillGivesItsFirstRow(@TempDir dir: File) {
        val file = File(dir, "comparison_aimi_smb.csv")
        file.writeText(currentRow() + "\n" + currentRow() + "\n")

        assertThat(parser.parse(file)).hasSize(2)
    }

    @Test
    fun aShortOrBrokenRowIsSkipped(@TempDir dir: File) {
        val file = write(dir, "1758304800000,2026-09-19 18:40:00,142.0", currentRow())

        assertThat(parser.parse(file)).hasSize(1)
    }

    @Test
    fun theCauseFlagsOfACurrentRowAreRead(@TempDir dir: File) {
        val entry = parser.parse(write(dir, currentRow())).single()

        assertThat(entry.aimiFlagMealPriority).isTrue()
        assertThat(entry.aimiFlagRefractory).isFalse()
        assertThat(entry.aimiFlagThrottle).isFalse()
        assertThat(entry.aimiFlagCbf).isTrue()
        assertThat(entry.smbFlagRefractory).isTrue()
        assertThat(entry.smbFlagThrottle).isFalse()
        assertThat(entry.smbFlagCbf).isFalse()
        assertThat(entry.contextMealRise).isTrue()
        assertThat(entry.contextCobActive).isTrue()
        assertThat(entry.contextUamBias).isFalse()
        assertThat(entry.smbLastBolusAgeMin).isEqualTo(22.5)
    }

    @Test
    fun anEmptyLastBolusAgeBecomesNull(@TempDir dir: File) {
        val row = currentRow(mapOf("SMB_LastBolusAgeMin" to ""))

        val entry = parser.parse(write(dir, row)).single()

        assertThat(entry.smbLastBolusAgeMin).isNull()
        // The row is still read in full, the empty column does not shift anything.
        assertThat(entry.contextCobActive).isTrue()
        assertThat(entry.reasonSmb).isEqualTo("SMB interval=3; lastBolusAge=22.5")
    }

    @Test
    fun theCausesOfACurrentRowAreListedInReadingOrder(@TempDir dir: File) {
        val entry = parser.parse(write(dir, currentRow())).single()

        assertThat(entry.causes()).containsExactly(
            DivergenceCause.AIMI_MEAL_PRIORITY,
            DivergenceCause.AIMI_CBF,
            DivergenceCause.SMB_REFRACTORY,
            DivergenceCause.CONTEXT_MEAL_RISE,
            DivergenceCause.CONTEXT_COB_ACTIVE
        ).inOrder()
    }

    @Test
    fun anOldRowWithTheVerdictBlockKeepsTheCauseDefaults(@TempDir dir: File) {
        val entry = parser.parse(write(dir, legacyRowWithVerdict())).single()

        assertThat(entry.aimiFlagMealPriority).isFalse()
        assertThat(entry.aimiFlagRefractory).isFalse()
        assertThat(entry.aimiFlagThrottle).isFalse()
        assertThat(entry.aimiFlagCbf).isFalse()
        assertThat(entry.smbFlagRefractory).isFalse()
        assertThat(entry.smbFlagThrottle).isFalse()
        assertThat(entry.smbFlagCbf).isFalse()
        assertThat(entry.contextMealRise).isFalse()
        assertThat(entry.contextCobActive).isFalse()
        assertThat(entry.contextUamBias).isFalse()
        assertThat(entry.smbLastBolusAgeMin).isNull()
        assertThat(entry.causes()).isEmpty()
    }

    @Test
    fun theOldestRowKeepsTheCauseDefaults(@TempDir dir: File) {
        val entry = parser.parse(write(dir, legacyRowWithoutVerdict())).single()

        assertThat(entry.aimiFlagMealPriority).isFalse()
        assertThat(entry.aimiFlagCbf).isFalse()
        assertThat(entry.smbFlagRefractory).isFalse()
        assertThat(entry.contextMealRise).isFalse()
        assertThat(entry.contextCobActive).isFalse()
        assertThat(entry.smbLastBolusAgeMin).isNull()
        assertThat(entry.causes()).isEmpty()
    }

    @Test
    fun aCriticalMomentCarriesTheCausesAndTheArtifactFlag(@TempDir dir: File) {
        val entries = parser.parse(write(dir, currentRow()))

        val moment = parser.findCriticalMoments(entries).single()

        assertThat(moment.artifactFlag).isEqualTo("VALID")
        assertThat(moment.verdict).isEqualTo("AIMI_AGGRESSIVE")
        assertThat(moment.causes).containsExactly(
            DivergenceCause.AIMI_MEAL_PRIORITY,
            DivergenceCause.AIMI_CBF,
            DivergenceCause.SMB_REFRACTORY,
            DivergenceCause.CONTEXT_MEAL_RISE,
            DivergenceCause.CONTEXT_COB_ACTIVE
        ).inOrder()
    }

    @Test
    fun aLegacyRowWhoseReasonTextIsOneDoesNotBecomeAFlag(@TempDir dir: File) {
        val entry = parser.parse(write(dir, legacyRowWithVerdictAndReasonTextOne())).single()

        // These two positions coincide with Reason_AIMI/Reason_SMB on this 37-column row. Without the
        // FLAG_COLUMNS gate, the ungated index read would see the literal "1" placed there and report
        // both flags as true.
        assertThat(entry.aimiFlagMealPriority).isFalse()
        assertThat(entry.aimiFlagRefractory).isFalse()
        assertThat(entry.reasonAimi).isEqualTo("1")
        assertThat(entry.reasonSmb).isEqualTo("1")
    }

    @Test
    fun aCriticalMomentOfAnOldRowHasNoCause(@TempDir dir: File) {
        val entries = parser.parse(write(dir, legacyRowWithVerdict()))

        val moment = parser.findCriticalMoments(entries).single()

        assertThat(moment.artifactFlag).isEqualTo("VALID")
        assertThat(moment.causes).isEmpty()
    }
}
