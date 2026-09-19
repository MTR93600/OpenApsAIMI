package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import app.aaps.shared.tests.AAPSLoggerTest
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.math.exp

/**
 * STALE_TRAINING_MS / force-after-inactivity gate on [BasalMlTrainingCoordinator].
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `b4f5704564` (unchanged on tip `a546722609`).
 * Study source set: the coordinator is androidMain (`AimiStorage`, `aimiWallClockMs`), so this
 * lives in `androidHostTest` + mockito. The tip file also holds parser / spread / pref-decoupling
 * locks (19 `@Test`); those are not this lot — study had no coordinator tests yet, and P3.7 is
 * the stale gate only.
 *
 * Names are camelCase (no backtick commas), same layout as
 * [app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionManagerRevertTest].
 */
class BasalMlTrainingCoordinatorTest : TestBase() {

    @TempDir
    lateinit var tempDir: File

    private lateinit var csvFile: File
    private lateinit var storage: AimiStorage
    private lateinit var learner: BasalNeuralLearner

    @BeforeEach
    fun setupCoordinatorHarness() {
        csvFile = File(tempDir, "basal_adaptive_records.csv")
        writeSyntheticCsv(csvFile, rowCount = 120)

        val helper = mock<AimiStorageHelper>()
        whenever(helper.getAimiFile("basal_adaptive_records.csv")).thenReturn(csvFile)
        whenever(helper.getAimiFile("basal_adaptive_weights.json")).thenReturn(File(tempDir, "basal_adaptive_weights.json"))
        whenever(helper.getAimiFile("t3c_brain_weights.json")).thenReturn(File(tempDir, "t3c_brain_weights.json"))
        whenever(helper.getAimiFile("basal_ml_training_state.json")).thenReturn(File(tempDir, "basal_ml_training_state.json"))

        storage = AndroidAimiStorage(helper)
        val preferences = mock<Preferences>()
        learner = BasalNeuralLearner(preferences, storage, aapsLogger)
    }

    @Test
    fun skipsWhenFewerThanMinNewRowsSinceLastTrain() = runBlocking {
        // A weights file already exists: this is the steady-state gate, not bootstrap, so MIN_NEW_ROWS
        // must still apply. lastTrainMs sits between TRAIN_INTERVAL_MS (1h) and STALE_TRAINING_MS (4h)
        // so neither the rate-limit skip nor the staleness bypass also apply — MIN_NEW_ROWS must be
        // the only thing this test exercises.
        //
        // Tip wrote `now - 5min`, which is inside TRAIN_INTERVAL_MS and would skip at the rate-limit
        // gate before this one. Study places lastTrainMs at 2h so the assertion matches the comment
        // (MIN_NEW_ROWS only). Clock is aimiWallClockMs(), not System.currentTimeMillis().
        File(tempDir, "basal_adaptive_weights.json").writeText("{}")
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        val twoHoursAgo = aimiWallClockMs() - 2L * 60 * 60_000L
        stateFile.writeText("""{"lastTrainMs":$twoHoursAgo,"rowsAtLastTrain":110}""")

        val log = CapturingLogger()
        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, log)
        val outcome = freshCoordinator.runScheduledTraining()
        assertThat(outcome).isEqualTo(BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED)
        assertThat(log.debugMessages.any { it.contains("new rows (need") }).isTrue()
    }

    @Test
    fun aStaleLastTrainedTimestampForcesTrainingEvenWithTooFewNewRows() = runBlocking {
        // Same setup as the min-new-rows skip above (too few new rows since last train), except
        // lastTrainMs is old enough to cross STALE_TRAINING_MS (4h) — training must be forced anyway,
        // proven the same deterministic way the tip uses: the "label set" debug log fires only once
        // the CSV has been parsed, and the min-new-rows skip line must not appear.
        File(tempDir, "basal_adaptive_weights.json").writeText("{}")
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        val fiveHoursAgo = aimiWallClockMs() - 5L * 60 * 60_000L
        stateFile.writeText("""{"lastTrainMs":$fiveHoursAgo,"rowsAtLastTrain":110}""")

        val log = CapturingLogger()
        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, log)
        freshCoordinator.runScheduledTraining()

        assertThat(log.debugMessages.any { it.contains("label set") }).isTrue()
        assertThat(log.debugMessages.any { it.contains("new rows (need") }).isFalse()
    }

    @Test
    fun bootstrapIsNotBlockedByMinNewRowsWhenNoWeightsExistYet() = runBlocking {
        // No basal_adaptive_weights.json here (bootstrapNeeded = true). rowsAtLastTrain is set high enough
        // that newRows would be far below MIN_NEW_ROWS under the steady-state gate, simulating a persisted
        // counter left over from before a row-filtering rule change. The bootstrap path must still reach
        // training instead of waiting forever for newRows to climb back above the threshold.
        //
        // The eventual publish is stochastic (unseeded net), so this only asserts on the one thing the fix
        // changed: the MIN_NEW_ROWS gate must not be the reason for a skip.
        val log = CapturingLogger()
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        stateFile.writeText("""{"lastTrainMs":0,"rowsAtLastTrain":100000}""")

        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, log)
        freshCoordinator.runScheduledTraining()

        assertThat(log.debugMessages.any { it.contains("new rows (need") }).isFalse()
    }

    private class CapturingLogger : AAPSLoggerTest() {
        val debugMessages = mutableListOf<String>()

        override fun debug(tag: LTag, message: String) {
            debugMessages += message
            super.debug(tag, message)
        }
    }

    /**
     * BG closes about 60 % of the gap to target every 30 min, so it stays above target and really
     * falls: a correction the parser can score. Same fixture as the tip coordinator tests.
     */
    private fun writeSyntheticCsv(file: File, rowCount: Int) {
        val bgs = List(rowCount) { i -> 100.0 + 45.0 * exp(-0.1529 * i) }
        writeCsv(file, bgs)
    }

    /**
     * Writes a `basal_adaptive_records.csv` at 5-min cadence.
     *
     * eventualBg is deliberately floored to 39 on every row, to prove the label comes from the realized
     * future BG and not from that prediction column.
     */
    private fun writeCsv(file: File, bgs: List<Double>, target: Double = 100.0) {
        val header = "timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale"
        val startTs = 1_700_000_000_000L
        val stepMs = 5L * 60_000
        val lines = buildList {
            add(header)
            bgs.forEachIndexed { i, bg ->
                val ts = startTs + i * stepMs
                add("$ts,$bg,39.0,1.0,$target,0.1,30,45,0.5,1.0,1.0")
            }
        }
        file.writeText(lines.joinToString("\n"))
    }
}
