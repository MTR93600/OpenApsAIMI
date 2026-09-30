package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trainer's report line, which is the only place its losses are ever shown.
 *
 * It used to be built with `"%.4f".format(..)`, which does not exist outside the JVM. The
 * replacement is `NumberFormat.withDecimals(4)`, and it is **not** byte for byte the same function:
 * `String.format` rounds half up and follows the device's decimal separator, `NumberFormat` rounds
 * half even and is given an explicit dot here. This is log text and nothing parses it, so that
 * difference is acceptable - but the shape has to survive, and so does `NaN`.
 *
 * `NaN` is the case worth a test rather than an argument: `incumbentLoss` is `NaN` on every run where
 * no model is installed yet, which is every first run on every phone. A formatter that rendered it as
 * an empty string, a zero or a replacement character would make "no model yet" unreadable in exactly
 * the logs someone would go looking at.
 */
class AutodriveNeuralTrainerReportTest {

    private val logger = RecordingAapsLogger()
    private val storage = InMemoryAimiStorage()
    private val trainer = AutodriveNeuralTrainer(logger, storage)

    private fun reportLine(): String =
        logger.infoLines.singleOrNull { it.startsWith("NeuralTrainer:") }
            ?: error("expected exactly one report line, got ${logger.infoLines}")

    /**
     * A corpus the trainer will actually fit: enough rows, enough positives on both sides of the
     * chronological split, and every row at the current schema version so none is skipped.
     */
    private fun writeCorpus(rows: Int = 1000) {
        val lines = mutableListOf(AutodriveDatasetSchema.HEADER)
        repeat(rows) { i ->
            val hypo = if (i % 10 == 0) 1 else 0
            // The mask carries the label, so the fit has something real to find.
            val mask = if (hypo == 1) "0.9|0.8|0.7" else "0.1|0.2|0.3"
            lines += listOf(
                (1_700_000_000_000L + i * 300_000L).toString(), // Timestamp_Epoch
                "2026-01-01 00:00:00",                          // Date - never read
                "120.0", "0.5", "1.000", "0.0", "0.0100", "0.000", "70.0",
                mask,
                "0.000", "0.000", "0.000", "0.000", "0",
                "100.0",                                        // Future_BG_45m - must not be blank
                hypo.toString(),                                // Hypo_Occurred
                "0",                                            // Hyper_Occurred
                "1",                                            // Engaged
                AutodriveDatasetSchema.CURRENT_VERSION.toString(),
            ).joinToString(",")
        }
        storage.files[storage.file(AutodriveDataLake.FILE_NAME).value] = lines.joinToString("\n") + "\n"
    }

    @Test
    fun anAbsentIncumbentIsReportedAsNaNRatherThanAsANumber() {
        writeCorpus()

        trainer.trainAttentionWeights()

        assertTrue(
            reportLine().contains("incumbent=NaN)"),
            "no weights file is installed, so the incumbent loss must read NaN: ${reportLine()}",
        )
    }

    /** Four decimals and a dot, whatever the machine's locale is. */
    @Test
    fun lossesAreFourDecimalsWithADot() {
        writeCorpus()

        trainer.trainAttentionWeights()

        val line = reportLine()
        assertTrue(
            Regex("""loss cand=\d+\.\d{4} base=\d+\.\d{4} incumbent=NaN\)""").containsMatchIn(line),
            "the losses must keep the `0.1234` shape the old `%.4f` produced: $line",
        )
    }

    /** An unreadable corpus reports every loss as NaN, and still forms a readable line. */
    @Test
    fun anUnreadableDatasetReportsEveryLossAsNaN() {
        trainer.trainAttentionWeights()

        val line = reportLine()
        assertTrue(line.contains("dataset unreadable"), line)
        assertTrue(line.contains("loss cand=NaN base=NaN incumbent=NaN)"), line)
    }

    @Test
    fun theReportIsAlsoExposedForTheLivenessExport() {
        writeCorpus()

        trainer.trainAttentionWeights()

        assertEquals(1000, trainer.lastReport?.rows)
    }

    /** Keeps the `info` lines, because that is where the report goes. `commonTest` has no Mockito. */
    private class RecordingAapsLogger : AAPSLogger {

        val infoLines: MutableList<String> = mutableListOf()

        override fun info(tag: LTag, message: String) {
            infoLines += message
        }

        override fun debug(message: String) = Unit
        override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, accessor: () -> String) = Unit
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun warn(tag: LTag, message: String) = Unit
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(tag: LTag, message: String) = Unit
        override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
        override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
        override fun error(format: String, vararg arguments: Any?) = Unit
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    }
}
