package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * File/store half of the `6c0c0285ff` corpus contract.
 *
 * The rewrite rule and the header guard are locked in commonTest
 * ([AimiSmbCorpusGuardTest]). These tests cover the androidMain File wrapper and
 * [AimiSmbModelStore.delete], which commonMain cannot see. No mocks.
 *
 * Dose-facing: deleting the weight file (and, on this study tree, its `.bak` / `.tmp` siblings)
 * leaves `refine()` on the rule-based dose until a clean training run publishes new weights.
 */
class AimiSmbCorpusFileTest {

    private val staleProductionHeader: List<String> = listOf(
        "dateStr",
        "bg",
        "iob",
        "cob",
        "delta",
        "shortAvgDelta",
        "longAvgDelta",
        "tdd7DaysPerHour",
        "tdd2DaysPerHour",
        "tddPerHour",
        "tdd24HrsPerHour",
        "predictedSMB",
        "smbGiven",
    )

    private fun currentShapeRow(smbGiven: String = "0.0000"): String = listOf(
        "09/07/2026 12:30",
        "152", "1.2", "9.0", "3.5", "2.9", "1.7", "0.8", "0.7", "0.9", "1.0",
        "0.4100", "0.9293", "0.9100", "0.6400",
        "0.9000", "0.1800", "0.8400",
        "0.8300", "0.1200", "0.7900",
        "2", "4", "3", "1", "3",
        "0.20", "0.10", "",
        "0.15", smbGiven, "55", "7.0",
        "0.1500", "0.0000", "governed", "harmonia", "148.0", "0.2000",
    ).joinToString(",")

    private fun legacyShapeRow(smbGiven: String = "0.3000"): String =
        currentShapeRow(smbGiven = smbGiven).split(",").take(33).joinToString(",")

    @Test
    fun a_stale_header_is_replaced_in_place_and_every_data_row_is_kept(@TempDir dir: File) {
        val file = File(dir, "oapsaimiML2_records.csv")
        val dataRows = listOf(legacyShapeRow(), currentShapeRow())
        file.writeText(staleProductionHeader.joinToString(", ") + "\n" + dataRows.joinToString("\n") + "\n")

        val outcome = TrainingCsvHeader.ensureCurrent(file, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        assertThat(outcome).isEqualTo(TrainingCsvHeader.Outcome.REPLACED)
        val lines = file.readLines()
        assertThat(lines.first()).isEqualTo(SmbRefinementFeatureSchema.trainingCsvHeaderLine())
        assertThat(lines.drop(1)).isEqualTo(dataRows)
        assertThat(dir.listFiles()!!.map { it.name }).containsExactly("oapsaimiML2_records.csv")
    }

    @Test
    fun a_current_header_is_left_alone(@TempDir dir: File) {
        val file = File(dir, "oapsaimiML2_records.csv")
        val content = SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n" + currentShapeRow() + "\n"
        file.writeText(content)

        val outcome = TrainingCsvHeader.ensureCurrent(file, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        assertThat(outcome).isEqualTo(TrainingCsvHeader.Outcome.ALREADY_CURRENT)
        assertThat(file.readText()).isEqualTo(content)
    }

    @Test
    fun a_missing_file_is_created_with_the_current_header(@TempDir dir: File) {
        val file = File(dir, "oapsaimiML2_records.csv")

        val outcome = TrainingCsvHeader.ensureCurrent(file, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        assertThat(outcome).isEqualTo(TrainingCsvHeader.Outcome.CREATED)
        assertThat(file.readLines()).containsExactly(SmbRefinementFeatureSchema.trainingCsvHeaderLine())
    }

    @Test
    fun deleting_the_weight_file_leaves_refine_on_the_rule_based_dose(@TempDir dir: File) {
        val weights = AimiSmbModelStore.modelFile(dir)
        weights.writeText("{}")
        File(dir, weights.name + ".bak").writeText("{}")
        assertThat(weights.exists()).isTrue()

        assertThat(AimiSmbModelStore.delete(dir)).isTrue()

        assertThat(weights.exists()).isFalse()
        assertThat(File(dir, weights.name + ".bak").exists()).isFalse()
        // With no model in memory, refine hands the rule-based dose back untouched.
        val predictedSmb = 0.62f
        assertThat(
            AimiSmbTrainer.refine(
                predictedSmb = predictedSmb,
                features = FloatArray(SmbRefinementFeatureSchema.INPUT_SIZE) { 0f },
            )
        ).isEqualTo(predictedSmb)
    }

    @Test
    fun deleting_an_absent_weight_file_reports_success(@TempDir dir: File) {
        assertThat(AimiSmbModelStore.delete(dir)).isTrue()
    }
}
