package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Specification locks for the enriched `oapsaimiML2_records.csv` corpus.
 *
 * These are locks on new code, not a red-before / green-after proof. They freeze three promises:
 *  - adding columns at the end of the header does not change what the existing parser reads;
 *  - an unknown origin field stays empty and is never read as zero;
 *  - an outcome that arrives outside the acceptance window is not written into the row.
 */
class SmbTrainingRowBufferTest {

    /** The header as it was before the origin and outcome columns were added. */
    private val legacyHeaders: List<String> = buildList {
        add("dateStr")
        addAll(SmbRefinementFeatureSchema.csvFeatureNames)
        addAll(SmbRefinementFeatureSchema.familyAuditFeatureNames)
        addAll(SmbRefinementFeatureSchema.optionalTrainingAuditFeatureNames)
        add("predictedSMB")
        add("smbGiven")
        add("dynamicPeak")
        add("adjustedDia")
    }

    /** The header written today: the same columns, then the six new ones. */
    private val enrichedHeaders: List<String> = legacyHeaders + SmbTrainingRowBuffer.ADDED_COLUMN_NAMES

    /** One plausible row for [legacyHeaders]: numbers everywhere, empty conflict flags. */
    private val legacyCols: List<String> = legacyHeaders.map { name ->
        when (name) {
            "dateStr"                             -> "05/09/2026 12:30"
            "decisionConflictFlags"               -> ""
            "eventMemoryCorrectionFragilityScore" -> "0.10"
            "eventMemoryPostHyperExhaustionScore" -> "0.20"
            "causalLearningQuality"               -> "0.90"
            "causalProtectiveConfidence"          -> "0.30"
            "smbGiven"                            -> "0.45"
            else                                  -> "1.25"
        }
    }

    @Test
    fun `the enriched CSV stays readable by the existing parser`() {
        val enrichedCols = legacyCols + listOf("0.0000", "0.3000", "AUTODRIVE_FLOOR", "GlobalAIMI", "142.0", "1.5000")

        val legacyFeatures = SmbRefinementFeatureSchema.parseTrainingFeatures(legacyHeaders, legacyCols)
        val enrichedFeatures = SmbRefinementFeatureSchema.parseTrainingFeatures(enrichedHeaders, enrichedCols)

        assertThat(legacyFeatures).isNotNull()
        assertThat(enrichedFeatures).isNotNull()
        assertThat(enrichedFeatures!!.toList()).isEqualTo(legacyFeatures!!.toList())

        assertThat(SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(enrichedHeaders, enrichedCols, enrichedFeatures))
            .isEqualTo(SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(legacyHeaders, legacyCols, legacyFeatures))

        // The label is read by name, so it must still be the same cell in both shapes.
        assertThat(enrichedCols[enrichedHeaders.indexOf("smbGiven")])
            .isEqualTo(legacyCols[legacyHeaders.indexOf("smbGiven")])
    }

    @Test
    fun `an empty origin column is not read as zero`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 1_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))

        // No stampOrigin call: the tick never reached the export point.
        val written = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
        assertThat(written).hasSize(1)

        val cols = written.first().split(",")
        assertThat(cols).hasSize(enrichedHeaders.size)
        SmbTrainingRowBuffer.ADDED_COLUMN_NAMES.forEach { name ->
            val value = cols[enrichedHeaders.indexOf(name)]
            assertThat(value).isEmpty()
            assertThat(value).isNotEqualTo("0")
            assertThat(value.toDoubleOrNull()).isNull()
        }
    }

    @Test
    fun `a stamped origin keeps the model output and the floor apart`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 2_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))
        buffer.stampOrigin(
            tickKey = t0,
            smbModelU = 0.0,
            smbFloorU = 0.35,
            bindingStage = "AUTODRIVE_FLOOR",
            originOwner = "GlobalAIMI",
            smbMpcRequestedU = null,
        )

        val cols = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
            .single()
            .split(",")

        assertThat(cols[enrichedHeaders.indexOf("smbModelU")].toDouble()).isWithin(1e-9).of(0.0)
        assertThat(cols[enrichedHeaders.indexOf("smbFloorU")].toDouble()).isWithin(1e-9).of(0.35)
        assertThat(cols[enrichedHeaders.indexOf("smbBindingStage")]).isEqualTo("AUTODRIVE_FLOOR")
        assertThat(cols[enrichedHeaders.indexOf("smbOriginOwner")]).isEqualTo("GlobalAIMI")
    }

    @Test
    fun `the pre-barrier request is kept apart from the post-barrier model output`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 8_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))
        // The solver asked for 1.5 U and the barrier allowed nothing.
        buffer.stampOrigin(
            tickKey = t0,
            smbModelU = 0.0,
            smbFloorU = null,
            bindingStage = null,
            originOwner = "AutodriveV3",
            smbMpcRequestedU = 1.5,
        )

        val cols = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
            .single()
            .split(",")

        assertThat(cols[enrichedHeaders.indexOf("smbMpcRequestedU")].toDouble()).isWithin(1e-9).of(1.5)
        assertThat(cols[enrichedHeaders.indexOf("smbModelU")].toDouble()).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `an unknown pre-barrier request stays empty and is never read as zero`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 9_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))
        // A tick that did not engage Autodrive: the model output is known, the request is not.
        buffer.stampOrigin(
            tickKey = t0,
            smbModelU = 0.0,
            smbFloorU = null,
            bindingStage = null,
            originOwner = "GlobalAIMI",
            smbMpcRequestedU = null,
        )

        val cols = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
            .single()
            .split(",")

        val cell = cols[enrichedHeaders.indexOf("smbMpcRequestedU")]
        assertThat(cell).isEmpty()
        assertThat(cell).isNotEqualTo("0")
        assertThat(cell.toDoubleOrNull()).isNull()
        // The model output of the same row is a real zero, so the two cannot be confused.
        assertThat(cols[enrichedHeaders.indexOf("smbModelU")].toDouble()).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `the new column is added last so existing column indexes do not move`() {
        assertThat(SmbTrainingRowBuffer.ADDED_COLUMN_NAMES.last()).isEqualTo("smbMpcRequestedU")
        assertThat(enrichedHeaders.indexOf("smbModelU")).isEqualTo(legacyHeaders.size)
        assertThat(enrichedHeaders.indexOf("bgRealisedAfter")).isEqualTo(legacyHeaders.size + 4)
    }

    @Test
    fun `an outcome outside the acceptance window is not written`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 3_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))

        // Too early, then too late: neither reading may be used as the outcome of this row.
        buffer.fillRealisedOutcomes(nowMs = t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MIN_MS - 1, observedBg = 111.0)
        buffer.fillRealisedOutcomes(nowMs = t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1, observedBg = 222.0)

        val cols = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
            .single()
            .split(",")
        assertThat(cols[enrichedHeaders.indexOf("bgRealisedAfter")]).isEmpty()
    }

    @Test
    fun `an outcome inside the acceptance window is written`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 4_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))

        buffer.fillRealisedOutcomes(nowMs = t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MS, observedBg = 142.0)
        // A later reading must not overwrite the one already accepted.
        buffer.fillRealisedOutcomes(nowMs = t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS, observedBg = 199.0)

        val cols = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
            .single()
            .split(",")
        assertThat(cols[enrichedHeaders.indexOf("bgRealisedAfter")].toDouble()).isWithin(1e-9).of(142.0)
    }

    @Test
    fun `a row still inside its window is not written yet`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 5_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))

        assertThat(buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MS)).isEmpty()
        assertThat(buffer.pendingCount()).isEqualTo(1)
    }

    @Test
    fun `each tick stamps its own row`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 6_000_000L
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))
        buffer.stampOrigin(
            tickKey = t0,
            smbModelU = 0.10,
            smbFloorU = null,
            bindingStage = "SMB_EXECUTOR",
            originOwner = "A",
            smbMpcRequestedU = null,
        )
        buffer.enqueue(timestampMs = t0 + 300_000, valuesPrefix = legacyCols.joinToString(","))
        buffer.stampOrigin(
            tickKey = t0 + 300_000,
            smbModelU = 0.20,
            smbFloorU = null,
            bindingStage = "PKPD_GUARD",
            originOwner = "B",
            smbMpcRequestedU = null,
        )

        val rows = buffer.drainWritableRows(t0 + 300_000 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
        assertThat(rows).hasSize(2)
        assertThat(rows[0].split(",")[enrichedHeaders.indexOf("smbOriginOwner")]).isEqualTo("A")
        assertThat(rows[1].split(",")[enrichedHeaders.indexOf("smbOriginOwner")]).isEqualTo("B")
    }

    @Test
    fun `a tick that queued no row never stamps the row of another tick`() {
        val buffer = SmbTrainingRowBuffer()
        val t0 = 7_000_000L
        // The tick at t0 queues a row but leaves before the export point, so it stays unstamped.
        buffer.enqueue(timestampMs = t0, valuesPrefix = legacyCols.joinToString(","))
        // The next tick queues nothing yet reaches the export point.
        buffer.stampOrigin(
            tickKey = t0 + 300_000,
            smbModelU = 9.99,
            smbFloorU = 9.99,
            bindingStage = "OTHER",
            originOwner = "OTHER_TICK",
            smbMpcRequestedU = 9.99,
        )

        val cols = buffer.drainWritableRows(t0 + SmbTrainingRowBuffer.OUTCOME_HORIZON_MAX_MS + 1)
            .single()
            .split(",")

        assertThat(cols[enrichedHeaders.indexOf("smbOriginOwner")]).isEmpty()
        assertThat(cols[enrichedHeaders.indexOf("smbModelU")]).isEmpty()
    }
}
