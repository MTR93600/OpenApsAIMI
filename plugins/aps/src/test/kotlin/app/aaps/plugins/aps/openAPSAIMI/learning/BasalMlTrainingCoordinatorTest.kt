package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.exp

class BasalMlTrainingCoordinatorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var csvFile: File
    private lateinit var storage: AimiStorageHelper
    private lateinit var preferences: Preferences
    private lateinit var learner: BasalNeuralLearner
    private lateinit var coordinator: BasalMlTrainingCoordinator

    @BeforeEach
    fun setup() {
        csvFile = File(tempDir, "basal_adaptive_records.csv")
        writeSyntheticCsv(csvFile, rowCount = 120)

        val context = mockk<Context>(relaxed = true)
        preferences = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        val log = mockk<AAPSLogger>(relaxed = true)

        every { storage.getAimiFile("basal_adaptive_records.csv") } returns csvFile
        every { storage.getAimiFile("basal_adaptive_weights.json") } returns File(tempDir, "basal_adaptive_weights.json")
        every { storage.getAimiFile("t3c_brain_weights.json") } returns File(tempDir, "t3c_brain_weights.json")
        every { storage.getAimiFile("basal_ml_training_state.json") } returns File(tempDir, "basal_ml_training_state.json")

        every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false

        learner = BasalNeuralLearner(context, preferences, storage, log)
        coordinator = BasalMlTrainingCoordinator(storage, learner, log)
    }

    @Test
    fun `parser aligns labels on realized future bg not floored eventualBg`() {
        val dataset = BasalMlDatasetParser.parse(csvFile)
        assertThat(dataset).isNotNull()
        // Tail rows without an observable +30min future are dropped (no fabricated label).
        assertThat(dataset!!.rowCount).isGreaterThan(100)
        assertThat(dataset.rowCount).isLessThan(120)
        assertThat(dataset.basalTargets).hasSize(dataset.rowCount)
        assertThat(dataset.t3cTargets).hasSize(dataset.rowCount)
        val basalTarget = dataset.basalTargets.first()[0]
        val t3cTarget = dataset.t3cTargets.first()[0]
        // Label clamp stays strictly inside the runtime clamp (runtime floor 0.80).
        assertThat(basalTarget).isAtLeast(0.85)
        assertThat(basalTarget).isAtMost(1.35)
        assertThat(t3cTarget).isAtLeast(0.5)
        assertThat(t3cTarget).isAtMost(2.0)
        // Regression guard: BG stays above target and falls too slowly, so the label must push basal UP.
        // If the parser still used the floored eventualBg=39 as the outcome, actualDelta would be huge and
        // it would learn to CUT instead — the exact contamination this fix removes.
        assertThat(basalTarget).isGreaterThan(1.0)
    }

    @Test
    fun `a rise below target is a mild cut, never the saturated floor`() {
        // BG 70 with target 100, climbing back to 95 within the label window: the user IS recovering.
        // Old rule: neededDelta = -30, actualDelta = -25 turned into a fake +1 by coerceAtLeast(1.0),
        // so the weight became -30 and every such row was labelled exactly 0.70 — a maximum cut on a
        // correct recovery, indistinguishable from the runtime floor.
        val file = File(tempDir, "rise_below_target.csv")
        writeCsv(file, bgs = listOf(70.0, 74.0, 78.0, 82.0, 86.0, 91.0, 95.0) + List(20) { 95.0 + it })

        val dataset = BasalMlDatasetParser.parse(file)!!
        val label = dataset.basalTargets.first()[0]

        assertThat(label).isNotWithin(1e-6).of(0.70)
        assertThat(label).isGreaterThan(0.85)   // graded, not saturated on the label floor
        assertThat(label).isLessThan(1.0)       // still below target, so a small cut is right
    }

    @Test
    fun `a rise while above target is rejected instead of getting a made-up label`() {
        // Mixed direction: BG is above target AND still climbing. The ratio has no meaning here, and the
        // old rule silently turned every one of these rows into the saturated 1.50.
        val file = File(tempDir, "mixed_direction.csv")
        writeCsv(file, bgs = List(30) { 150.0 + it * 2.0 })

        assertThat(BasalMlDatasetParser.parse(file)).isNull()
    }

    @Test
    fun `a flat window is rejected in both directions`() {
        val file = File(tempDir, "flat.csv")
        writeCsv(file, bgs = List(30) { 145.0 })

        assertThat(BasalMlDatasetParser.parse(file)).isNull()
    }

    @Test
    fun `a clean converging row gets a graded value, not one of three fixed points`() {
        // BG just above target and closing most of the gap inside the window.
        val file = File(tempDir, "converging.csv")
        writeCsv(file, bgs = listOf(112.0, 110.0, 108.0, 106.0, 105.0, 104.5, 104.0) + List(20) { 104.0 })

        val dataset = BasalMlDatasetParser.parse(file)!!
        val label = dataset.basalTargets.first()[0]

        // needed = 12, achieved = 8 → weight 12/8 = 1.5 → damped 1.25.
        assertThat(label).isWithin(1e-6).of(1.25)
    }

    @Test
    fun `a bolus inside the label window censors the row`() {
        val file = File(tempDir, "bolus_window.csv")
        val bgs = List(30) { 145.0 - it * 2.0 }
        writeCsv(file, bgs = bgs, bolusAt = mapOf(4 to 1.5))

        val dataset = BasalMlDatasetParser.parse(file)!!

        assertThat(dataset.stats.rejectedContaminated).isGreaterThan(0)
        // The explicit columns are present, so nothing is accepted blind.
        assertThat(dataset.stats.legacyUncensored).isEqualTo(0)
    }

    @Test
    fun `an unexplained IOB jump censors legacy rows that have no bolus column`() {
        val file = File(tempDir, "iob_jump.csv")
        val bgs = List(30) { 145.0 - it * 2.0 }
        writeCsv(file, bgs = bgs, iobAt = mapOf(4 to 2.5))

        val dataset = BasalMlDatasetParser.parse(file)!!

        assertThat(dataset.stats.rejectedContaminated).isGreaterThan(0)
        assertThat(dataset.stats.legacyUncensored).isGreaterThan(0)
    }

    @Test
    fun `parser appends physio context features and backfills neutral for legacy rows`() {
        // Legacy CSV (no physio columns) must still yield full-width inputs: 6 glucose-dynamics base + 10 physio
        // (mirror of the SMB schema), the physio slots filled with neutral values (schema versioning + backfill).
        val dataset = BasalMlDatasetParser.parse(csvFile)!!
        val input = dataset.inputs.first()

        assertThat(input.size).isEqualTo(BasalMlTrainingCoordinator.INPUT_SIZE)
        assertThat(input.size).isEqualTo(16)
        // Physio block starts at BASE_FEATURE_COUNT: mealProb neutral = 0.0, circadianSiFactor neutral = 1.0.
        assertThat(input[BasalMlTrainingCoordinator.BASE_FEATURE_COUNT]).isEqualTo(0.0f)
        assertThat(input[BasalMlTrainingCoordinator.BASE_FEATURE_COUNT + 2]).isEqualTo(1.0f)
    }

    @Test
    fun `training is reached even when both feature prefs are off (decoupled from usage)`() = runBlocking {
        // Old behavior returned SKIPPED before any training when the feature prefs were off. New contract: training
        // depends only on data availability; the prefs gate only runtime usage (BasalNeuralLearner). We assert the
        // training path is REACHED — via the "label set" debug log that fires right before training each head,
        // deterministically — rather than the stochastic publish result (the net is unseeded, and publishing no
        // longer reads an incumbent to compare against — see BasalMlTrainingCoordinator.trainAndMaybePublish).
        every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns false
        every { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false

        // Enough scorable windows to clear BASAL_MIN_ROWS. One episode cannot do it: see
        // writeMultiEpisodeCsv.
        writeMultiEpisodeCsv(csvFile, episodes = 12, rowsPerEpisode = 20)

        val logMock = mockk<AAPSLogger>(relaxed = true)
        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, logMock)
        freshCoordinator.runScheduledTraining()

        verify(atLeast = 1) { logMock.debug(any(), match<String> { it.contains("label set") }) }
    }

    @Test
    fun `representativeProbeInput averages training features`() {
        val inputs = listOf(
            floatArrayOf(100f, 1f, 0f, 30f, 45f, 0.5f) + FloatArray(10) { 0f },
            floatArrayOf(200f, 2f, 0f, 60f, 90f, 1.0f) + FloatArray(10) { 1f },
        )
        val probe = BasalMlDatasetParser.representativeProbeInput(inputs)
        assertThat(probe.size).isEqualTo(BasalMlTrainingCoordinator.INPUT_SIZE)
        assertThat(probe[0]).isWithin(0.01f).of(150f)
        assertThat(probe[1]).isWithin(0.01f).of(1.5f)
    }

    @Test
    fun `maybeTrainAsync is fire and forget`() {
        coordinator.maybeTrainAsync()
    }

    @Test
    fun `skips when fewer than min new rows since last train`() = runBlocking {
        // A weights file already exists: this is the steady-state gate, not bootstrap, so MIN_NEW_ROWS
        // must still apply. lastTrainMs is recent (well under STALE_TRAINING_MS) so the staleness bypass
        // does not also apply here — MIN_NEW_ROWS must be the only thing this test exercises.
        File(tempDir, "basal_adaptive_weights.json").writeText("{}")
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        stateFile.writeText("""{"lastTrainMs":${System.currentTimeMillis() - 5 * 60_000L},"rowsAtLastTrain":110}""")

        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, mockk(relaxed = true))
        val outcome = freshCoordinator.runScheduledTraining()
        assertThat(outcome).isEqualTo(BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED)
    }

    @Test
    fun `a stale last-trained timestamp forces training even with too few new rows`() = runBlocking {
        // Same setup as the min-new-rows skip above (too few new rows since last train), except
        // lastTrainMs is old enough to cross STALE_TRAINING_MS (4h) — training must be forced anyway,
        // proven the same deterministic way the "decoupled from usage" test above uses: the "label set"
        // debug log fires only once the min-new-rows gate has been cleared.
        File(tempDir, "basal_adaptive_weights.json").writeText("{}")
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        stateFile.writeText("""{"lastTrainMs":${System.currentTimeMillis() - 5 * 60 * 60_000L},"rowsAtLastTrain":110}""")

        val logMock = mockk<AAPSLogger>(relaxed = true)
        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, logMock)
        freshCoordinator.runScheduledTraining()

        verify(atLeast = 1) { logMock.debug(any(), match<String> { it.contains("label set") }) }
        verify(exactly = 0) { logMock.debug(any(), match<String> { it.contains("new rows (need") }) }
    }

    @Test
    fun `bootstrap is not blocked by min new rows when no weights exist yet`() = runBlocking {
        // No basal_adaptive_weights.json here (bootstrapNeeded = true). rowsAtLastTrain is set high enough
        // that newRows would be far below MIN_NEW_ROWS under the steady-state gate, simulating a persisted
        // counter left over from before a row-filtering rule change. The bootstrap path must still reach
        // training instead of waiting forever for newRows to climb back above the threshold.
        //
        // The eventual publish is stochastic (unseeded net), so this only asserts on the one thing the fix
        // changed: the MIN_NEW_ROWS gate must not be the reason for a skip. Same "label set" log proof the
        // "decoupled from usage" test above uses, checked for absence of the min-rows skip line instead.
        val logMock = mockk<AAPSLogger>(relaxed = true)
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        stateFile.writeText("""{"lastTrainMs":0,"rowsAtLastTrain":100000}""")

        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, logMock)
        freshCoordinator.runScheduledTraining()

        verify(exactly = 0) { logMock.debug(any(), match<String> { it.contains("new rows (need") }) }
    }

    // ---------------------------------------------------------------- the two windows must not drift apart

    /**
     * The publish gate and the runtime health probe must sample the SAME bg window.
     *
     * If they diverge, training can publish a model that its own loader then refuses: the trainer logs a success,
     * `BasalNeuralLearner` nulls the model and silently serves the heuristic, and nothing in the logs links the two.
     * That is worse than either probe alone, so it is asserted instead of documented.
     */
    @Test
    fun `the publish gate sweeps the same bg anchors as the runtime health probe`() {
        assertThat(BasalMlTrainingCoordinator.SPREAD_SWEEP_BG_MGDL)
            .isEqualTo(BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL)
        assertThat(BasalMlTrainingCoordinator.SPREAD_SWEEP_BG_MGDL).hasSize(3)
    }

    /**
     * And the publish threshold may never be LOWER than the runtime one, or every model between the two numbers is
     * published and then dropped at load time. The runtime constant is shared by both heads, which is why one
     * shared number is kept on the publish side too.
     */
    @Test
    fun `the publish spread threshold is not looser than the runtime one`() {
        assertThat(BasalMlTrainingCoordinator.MIN_OUTPUT_SPREAD)
            .isAtLeast(BasalNeuralLearner.PROBE_MIN_SPREAD)
    }

    /** A "must beat the median label" ratio of 1.0 or more would be no gate at all. */
    @Test
    fun `the baseline gate asks for a real improvement`() {
        assertThat(BasalMlTrainingCoordinator.MAX_BASELINE_MAE_RATIO).isLessThan(1.0)
        assertThat(BasalMlTrainingCoordinator.MAX_BASELINE_MAE_RATIO).isGreaterThan(0.0)
    }

    /**
     * The label window and the runtime clamp are one pair of numbers, not two copies.
     *
     * The T3C bounds must match exactly: a model can only have LEARNED a value inside the window its labels were
     * clamped into, so clamping the runtime output anywhere else would either cut real learning or serve
     * extrapolation. The basal FLOOR is the deliberate exception and the test states which way the gap must point:
     * the runtime floor stays strictly below the label floor, because when the two are equal a saturated label and
     * a dead model produce the very same multiplier — which is what hid a constant model for 40 days.
     */
    @Test
    fun `the label window and the runtime clamp are the same numbers`() {
        assertThat(BasalLabelWindow.T3C_MIN).isEqualTo(BasalNeuralLearner.RUNTIME_T3C_FACTOR_MIN)
        assertThat(BasalLabelWindow.T3C_MAX).isEqualTo(BasalNeuralLearner.RUNTIME_T3C_FACTOR_MAX)
        assertThat(BasalLabelWindow.BASAL_MIN).isGreaterThan(BasalNeuralLearner.RUNTIME_BASAL_FLOOR)
        // Both label windows stay strictly inside what their head is allowed to publish.
        assertThat(BasalLabelWindow.BASAL_MIN).isGreaterThan(BasalNeuralLearner.BASAL_PROBE_OUTPUT_MIN)
        assertThat(BasalLabelWindow.BASAL_MAX).isLessThan(BasalNeuralLearner.BASAL_PROBE_OUTPUT_MAX)
        assertThat(BasalLabelWindow.T3C_MIN).isGreaterThan(BasalNeuralLearner.T3C_PROBE_OUTPUT_MIN)
        assertThat(BasalLabelWindow.T3C_MAX).isLessThan(BasalNeuralLearner.T3C_PROBE_OUTPUT_MAX)
    }

    /**
     * A bolus reported ON the anchor row censors that row too.
     *
     * `bolusU` on row i is the insulin delivered at that tick plus whatever landed since the tick before, so it
     * acts during row i's own label window. Only the FUTURE rows used to be checked, and the IOB-jump fallback
     * cannot see this case either: the IOB is already high at the anchor, so there is no jump left to spot. The
     * fixture below keeps the IOB flat at 3.0 U for exactly that reason.
     */
    @Test
    fun `a bolus reported on the anchor row censors that row`() {
        val file = File(tempDir, "bolus_on_anchor.csv")
        val bgs = List(30) { 200.0 - it * 3.0 }
        writeCsv(file, bgs = bgs, bolusAt = mapOf(6 to 1.5), iobAll = 3.0)

        val dataset = BasalMlDatasetParser.parse(file)!!
        val keptBgs = dataset.inputs.map { it[0] }

        // Row 6 carries the bolus and is gone...
        assertThat(keptBgs).doesNotContain(bgs[6].toFloat())
        // ...while the next row, whose window the bolus no longer covers, is kept and labelled.
        assertThat(keptBgs).contains(bgs[7].toFloat())
        assertThat(dataset.stats.rejectedContaminated).isAtLeast(1)
    }

    private companion object {
        const val TARGET_MGDL = 100.0
        const val START_MGDL = 220.0
    }

    /**
     * Writes many short correction episodes instead of one long one.
     *
     * A correction that behaves itself converges on the target, so a single episode only holds a
     * handful of windows the parser can score: after the gap is closed the 30-min fall drops under
     * `MIN_ACTUAL_DELTA_MGDL` and the row is dropped, on purpose. The old single-episode fixture only
     * produced a full label set because the old rule used `coerceAtLeast(1.0)` and invented a 1 mg/dL
     * fall on flat data. Real data holds many episodes, so a fixture that needs a real label set has to
     * hold many too.
     *
     * Each episode starts [START_MGDL] above target and closes about 60 % of the gap every 30 min.
     */
    private fun writeMultiEpisodeCsv(file: File, episodes: Int, rowsPerEpisode: Int) {
        val bgs = buildList {
            repeat(episodes) {
                repeat(rowsPerEpisode) { i ->
                    add(TARGET_MGDL + (START_MGDL - TARGET_MGDL) * exp(-0.1529 * i))
                }
            }
        }
        writeCsv(file, bgs, target = TARGET_MGDL)
    }

    private fun writeSyntheticCsv(file: File, rowCount: Int) {
        // BG closes about 60 % of the gap to target every 30 min, so it stays above target and really
        // falls: a correction the parser can score. The old fixture held BG perfectly flat at 145, which
        // only produced a label because `coerceAtLeast(1.0)` invented a 1 mg/dL fall out of nothing.
        val bgs = List(rowCount) { i -> 100.0 + 45.0 * exp(-0.1529 * i) }
        writeCsv(file, bgs)
    }

    /**
     * Writes a `basal_adaptive_records.csv` at 5-min cadence.
     *
     * eventualBg is deliberately floored to 39 on every row, to prove the label comes from the realized
     * future BG and not from that prediction column. [bolusAt] adds the causal columns; without it the
     * file stays in the legacy shape (no bolus / carb column at all).
     */
    private fun writeCsv(
        file: File,
        bgs: List<Double>,
        target: Double = 100.0,
        bolusAt: Map<Int, Double>? = null,
        iobAt: Map<Int, Double> = emptyMap(),
        iobAll: Double = 0.5,
    ) {
        var header = "timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale"
        if (bolusAt != null) header += ",bolusU,cobG"
        val startTs = 1_700_000_000_000L
        val stepMs = 5L * 60_000
        val lines = buildList {
            add(header)
            bgs.forEachIndexed { i, bg ->
                val ts = startTs + i * stepMs
                val iob = iobAt[i] ?: iobAll
                var row = "$ts,$bg,39.0,1.0,$target,0.1,30,45,$iob,1.0,1.0"
                if (bolusAt != null) row += ",${bolusAt[i] ?: 0.0},0.0"
                add(row)
            }
        }
        file.writeText(lines.joinToString("\n"))
    }
}
