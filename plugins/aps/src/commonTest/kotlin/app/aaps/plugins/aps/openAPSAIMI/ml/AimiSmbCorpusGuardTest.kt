package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proof and locks for the `oapsaimiML2_records.csv` corpus contract.
 *
 * The header of that file used to be written only when the file was created, so a device that started
 * the file early kept a 13 name header while rows grew to 33, then 38, then 39 fields. The trainer
 * looks its label up by name, so `indexOf("smbGiven")` returned 12, and column 12 of a real row is
 * `endogenousGlucoseDrive`: a hormonal score bounded by 1, learned as if it were insulin units.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `6c0c0285ff`. Study source set: header names, the
 * corpus guard, and the header-rewrite rule are commonMain with no File I/O, so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [SmbTrainingRowBufferTest]. The File wrapper
 * ([TrainingCsvHeader.ensureCurrent]) and [AimiSmbModelStore.delete] stay in androidMain; their
 * contracts are locked here on the pure functions, plus a host test for the File/store path.
 *
 * These are locks on the training-correctness contract. One of them is dose-facing: discarding a
 * model trained on an unreadable corpus makes `refine()` return the rule-based dose until a clean
 * training run publishes new weights.
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
    fun `header line is unchanged character for character`() {
        val expected = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, " +
            "tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, mealProb, endogenousGlucoseDrive, " +
            "circadianSiFactor, transientResistanceProb, patientModeMealBias, " +
            "patientModeProtectionBias, contextIntentConfidence, causalMealConfidence, " +
            "causalProtectiveConfidence, causalLearningQuality, familyProtectionLevel, " +
            "familyMealLevel, familyStabilityLevel, familyPhysioLevel, familyAutonomyLevel, " +
            "eventMemoryPostHyperExhaustionScore, eventMemoryCorrectionFragilityScore, " +
            "decisionConflictFlags, predictedSMB, smbGiven, dynamicPeak, adjustedDia, smbModelU, " +
            "smbFloorU, smbBindingStage, smbOriginOwner, bgRealisedAfter, smbMpcRequestedU"

        assertEquals(expected, SmbRefinementFeatureSchema.trainingCsvHeaderLine())
        assertEquals(39, currentHeader.size)
        assertEquals(30, SmbRefinementFeatureSchema.targetColumnIndex)
    }

    @Test
    fun `every older smb schema is an exact prefix of the current one`() {
        // This is why the header may be replaced in place whatever its old shape: an older, shorter row
        // keeps every cell under the right name. The stale 13 name header is not a schema at all — it
        // never matched any row — which is why a prefix-only rewrite could never repair it.
        val schema33 = currentHeader.take(33)
        val schema38 = currentHeader.take(38)

        assertEquals(schema33, currentHeader.subList(0, 33))
        assertEquals(schema38, currentHeader.subList(0, 38))
        assertNotEquals(staleProductionHeader, currentHeader.take(staleProductionHeader.size))
    }

    // ---- FIX 2: the trainer refuses a corpus it cannot read ------------------

    @Test
    fun `trainer refuses a corpus whose stored header puts smbGiven at the wrong index`() {
        val row = currentShapeRow()
        assertEquals(39, row.split(",").size)
        // What the stale header makes the trainer believe, and what the row really holds there.
        assertEquals(12, staleProductionHeader.indexOf("smbGiven"))
        assertEquals("0.9293", row.split(",")[12])
        assertEquals("0.0000", row.split(",")[30])

        val corpus = AimiSmbCorpus.buildTrainingCorpus(staleProductionHeader, listOf(row))

        // The label the trainer would learn from. Before the guard it was 0.9293, the hormonal score.
        assertNull(corpus?.targets?.singleOrNull()?.singleOrNull())
        assertNull(corpus)
    }

    @Test
    fun `header check names the expected and the found index`() {
        val check = AimiSmbCorpus.checkCorpusHeader(staleProductionHeader)

        assertFalse(check.valid)
        assertTrue("expected at index 30" in check.reason)
        assertTrue("found at index 12" in check.reason)
    }

    @Test
    fun `a healthy corpus passes the guard and is read normally`() {
        val check = AimiSmbCorpus.checkCorpusHeader(currentHeader)
        assertTrue(check.valid)

        val corpus = AimiSmbCorpus.buildTrainingCorpus(currentHeader, listOf(currentShapeRow(smbGiven = "0.4500")))

        assertNotNull(corpus)
        assertEquals(0.45, corpus.targets.single().single(), 1e-9)
        assertEquals(SmbRefinementFeatureSchema.INPUT_SIZE, corpus.inputs.single().size)
    }

    @Test
    fun `a row wider than the header is dropped and the others are kept`() {
        // Two writes that got interleaved: the production file holds exactly one such row, 65 fields wide.
        val interleavedRow = currentShapeRow(smbGiven = "9.9999") + "," + currentShapeRow().split(",").take(26).joinToString(",")
        assertEquals(65, interleavedRow.split(",").size)

        val corpus = AimiSmbCorpus.buildTrainingCorpus(
            currentHeader,
            listOf(currentShapeRow(smbGiven = "0.4500"), interleavedRow, legacyShapeRow(smbGiven = "0.3000")),
        )

        assertNotNull(corpus)
        assertEquals(listOf(0.45, 0.30), corpus.targets.map { it.single() })
    }

    @Test
    fun `a row shorter than the header is kept because the schemas are nested`() {
        val corpus = AimiSmbCorpus.buildTrainingCorpus(currentHeader, listOf(legacyShapeRow(smbGiven = "0.2500")))

        assertNotNull(corpus)
        assertEquals(0.25, corpus.targets.single().single(), 1e-9)
    }

    // ---- FIX 3: the header is made current whatever its old shape ------------

    @Test
    fun `a stale header is replaced in place and every data row is kept`() {
        val dataRows = listOf(legacyShapeRow(), currentShapeRow())
        val existing = listOf(staleProductionHeader.joinToString(", ")) + dataRows

        val applied = TrainingCsvHeader.apply(existing, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        assertEquals(TrainingCsvHeader.Outcome.REPLACED, applied.outcome)
        assertEquals(SmbRefinementFeatureSchema.trainingCsvHeaderLine(), applied.lines.first())
        assertEquals(dataRows, applied.lines.drop(1))
    }

    @Test
    fun `a current header is left alone`() {
        val existing = listOf(SmbRefinementFeatureSchema.trainingCsvHeaderLine(), currentShapeRow())

        val applied = TrainingCsvHeader.apply(existing, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        assertEquals(TrainingCsvHeader.Outcome.ALREADY_CURRENT, applied.outcome)
        assertEquals(existing, applied.lines)
    }

    @Test
    fun `a missing file is created with the current header`() {
        val applied = TrainingCsvHeader.apply(null, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")

        assertEquals(TrainingCsvHeader.Outcome.CREATED, applied.outcome)
        assertEquals(listOf(SmbRefinementFeatureSchema.trainingCsvHeaderLine()), applied.lines)
    }

    @Test
    fun `a replaced header makes every real row width readable again`() {
        val dataRows = listOf(legacyShapeRow(smbGiven = "0.1000"), currentShapeRow(smbGiven = "0.2000"))
        val existing = listOf(staleProductionHeader.joinToString(", ")) + dataRows

        val applied = TrainingCsvHeader.apply(existing, SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n")
        val headers = applied.lines.first().split(",").map { it.trim() }
        val corpus = AimiSmbCorpus.buildTrainingCorpus(headers, applied.lines.drop(1))

        assertNotNull(corpus)
        assertEquals(listOf(0.10, 0.20), corpus.targets.map { it.single() })
    }

    // ---- FIX 4 (common half): refine with no model is the rule-based dose ----

    @Test
    fun `an unreadable corpus does not produce a training label from the column next to smbGiven`() {
        // Dose-facing documentation lock: the stale 13-name header would have trained on
        // endogenousGlucoseDrive (index 12). After discard, refine() has no model and returns
        // predictedSmb unchanged — the rule-based dose. This test holds the training-side half:
        // the guard refuses that corpus so those weights are never published again.
        val check = AimiSmbCorpus.checkCorpusHeader(staleProductionHeader)
        assertFalse(check.valid)
        assertEquals(30, SmbRefinementFeatureSchema.targetColumnIndex)
        assertEquals(12, staleProductionHeader.indexOf(SmbRefinementFeatureSchema.TARGET_COLUMN_NAME))
    }
}
