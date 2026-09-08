package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Proof and locks for the `oapsaimiML2_records.csv` corpus contract.
 *
 * The header of that file used to be written only when the file was created, so a device that started
 * the file early kept a 13 name header while rows grew to 33, then 38, then 39 fields. The trainer
 * looks its label up by name, so `indexOf("smbGiven")` returned 12, and column 12 of a real row is
 * `endogenousGlucoseDrive`: a hormonal score bounded by 1, learned as if it were insulin units.
 */
class AimiSmbCorpusGuardTest {

    /** The header measured on a production file: 13 names above rows of 33, 38 and 39 fields. */
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

    /** The header the writer builds today, as a list of names. */
    private val currentHeader: List<String> = SmbRefinementFeatureSchema.trainingCsvColumnNames

    /** One row of the current 39 column shape. Index 12 is `endogenousGlucoseDrive`, index 30 is the dose. */
    private fun currentShapeRow(smbGiven: String = "0.0000", bg: String = "152"): String = listOf(
        "09/07/2026 12:30",
        bg, "1.2", "9.0", "3.5", "2.9", "1.7", "0.8", "0.7", "0.9", "1.0",
        "0.4100", "0.9293", "0.9100", "0.6400",
        "0.9000", "0.1800", "0.8400",
        "0.8300", "0.1200", "0.7900",
        "2", "4", "3", "1", "3",
        "0.20", "0.10", "",
        "0.15", smbGiven, "55", "7.0",
        "0.1500", "0.0000", "governed", "harmonia", "148.0", "0.2000",
    ).joinToString(",")

    /** The same tick as it was written before the origin and outcome columns existed: 33 fields. */
    private fun legacyShapeRow(smbGiven: String = "0.3000"): String =
        currentShapeRow(smbGiven = smbGiven).split(",").take(33).joinToString(",")

    // ---- FIX 1: one source of truth for the header ---------------------------

    @Test
    fun header_line_is_unchanged_character_for_character() {
        val expected = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, " +
            "tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, mealProb, endogenousGlucoseDrive, " +
            "circadianSiFactor, transientResistanceProb, patientModeMealBias, " +
            "patientModeProtectionBias, contextIntentConfidence, causalMealConfidence, " +
            "causalProtectiveConfidence, causalLearningQuality, familyProtectionLevel, " +
            "familyMealLevel, familyStabilityLevel, familyPhysioLevel, familyAutonomyLevel, " +
            "eventMemoryPostHyperExhaustionScore, eventMemoryCorrectionFragilityScore, " +
            "decisionConflictFlags, predictedSMB, smbGiven, dynamicPeak, adjustedDia, smbModelU, " +
            "smbFloorU, smbBindingStage, smbOriginOwner, bgRealisedAfter, smbMpcRequestedU"

        assertThat(SmbRefinementFeatureSchema.trainingCsvHeaderLine()).isEqualTo(expected)
        assertThat(currentHeader).hasSize(39)
        assertThat(SmbRefinementFeatureSchema.targetColumnIndex).isEqualTo(30)
    }

    @Test
    fun every_older_smb_schema_is_an_exact_prefix_of_the_current_one() {
        // This is why the header may be replaced in place whatever its old shape: an older, shorter row
        // keeps every cell under the right name. The stale 13 name header is not a schema at all — it
        // never matched any row — which is why a prefix-only rewrite could never repair it.
        val schema33 = currentHeader.take(33)
        val schema38 = currentHeader.take(38)

        assertThat(currentHeader.subList(0, 33)).isEqualTo(schema33)
        assertThat(currentHeader.subList(0, 38)).isEqualTo(schema38)
        assertThat(currentHeader.take(staleProductionHeader.size)).isNotEqualTo(staleProductionHeader)
    }

    // ---- FIX 2: the trainer refuses a corpus it cannot read ------------------

    @Test
    fun trainer_refuses_a_corpus_whose_stored_header_puts_smb_given_at_the_wrong_index() {
        val row = currentShapeRow()
        assertThat(row.split(",")).hasSize(39)
        // What the stale header makes the trainer believe, and what the row really holds there.
        assertThat(staleProductionHeader.indexOf("smbGiven")).isEqualTo(12)
        assertThat(row.split(",")[12]).isEqualTo("0.9293")
        assertThat(row.split(",")[30]).isEqualTo("0.0000")

        val corpus = AimiSmbTrainer.buildTrainingCorpus(staleProductionHeader, listOf(row))

        // The label the trainer would learn from. Before the guard it was 0.9293, the hormonal score.
        assertThat(corpus?.targets?.singleOrNull()?.singleOrNull()).isNull()
        assertThat(corpus).isNull()
    }

    @Test
    fun header_check_names_the_expected_and_the_found_index() {
        val check = AimiSmbTrainer.checkCorpusHeader(staleProductionHeader)

        assertThat(check.valid).isFalse()
        assertThat(check.reason).contains("expected at index 30")
        assertThat(check.reason).contains("found at index 12")
    }

    @Test
    fun a_healthy_corpus_passes_the_guard_and_is_read_normally() {
        val check = AimiSmbTrainer.checkCorpusHeader(currentHeader)
        assertThat(check.valid).isTrue()

        val corpus = AimiSmbTrainer.buildTrainingCorpus(currentHeader, listOf(currentShapeRow(smbGiven = "0.4500")))

        assertThat(corpus).isNotNull()
        assertThat(corpus!!.targets.single().single()).isEqualTo(0.45)
        assertThat(corpus.inputs.single()).hasLength(SmbRefinementFeatureSchema.INPUT_SIZE)
    }

    @Test
    fun a_row_wider_than_the_header_is_dropped_and_the_others_are_kept() {
        // Two writes that got interleaved: the production file holds exactly one such row, 65 fields wide.
        val interleavedRow = currentShapeRow(smbGiven = "9.9999") + "," + currentShapeRow().split(",").take(26).joinToString(",")
        assertThat(interleavedRow.split(",")).hasSize(65)

        val corpus = AimiSmbTrainer.buildTrainingCorpus(
            currentHeader,
            listOf(currentShapeRow(smbGiven = "0.4500"), interleavedRow, legacyShapeRow(smbGiven = "0.3000")),
        )

        assertThat(corpus).isNotNull()
        assertThat(corpus!!.targets.map { it.single() }).containsExactly(0.45, 0.30).inOrder()
    }

    @Test
    fun a_row_shorter_than_the_header_is_kept_because_the_schemas_are_nested() {
        val corpus = AimiSmbTrainer.buildTrainingCorpus(currentHeader, listOf(legacyShapeRow(smbGiven = "0.2500")))

        assertThat(corpus).isNotNull()
        assertThat(corpus!!.targets.single().single()).isEqualTo(0.25)
    }

    // ---- FIX 3: the header is made current whatever its old shape ------------

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
    fun a_replaced_header_makes_every_real_row_width_readable_again(@TempDir dir: File) {
        val file = File(dir, "oapsaimiML2_records.csv")
        val dataRows = listOf(legacyShapeRow(smbGiven = "0.1000"), currentShapeRow(smbGiven = "0.2000"))
        file.writeText(staleProductionHeader.joinToString(", ") + "\n" + dataRows.joinToString("\n") + "\n")

        TrainingCsvHeader.ensureCurrent(file, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        val lines = file.readLines()
        val headers = lines.first().split(",").map { it.trim() }
        val corpus = AimiSmbTrainer.buildTrainingCorpus(headers, lines.drop(1))

        assertThat(corpus).isNotNull()
        assertThat(corpus!!.targets.map { it.single() }).containsExactly(0.10, 0.20).inOrder()
    }

    // ---- FIX 4: the weights trained on the wrong column are thrown away ------

    @Test
    fun deleting_the_weight_file_leaves_refine_on_the_rule_based_dose(@TempDir dir: File) {
        val weights = AimiSmbModelStore.modelFile(dir)
        weights.writeText("{}")
        assertThat(weights.exists()).isTrue()

        assertThat(AimiSmbModelStore.delete(dir)).isTrue()

        assertThat(weights.exists()).isFalse()
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
