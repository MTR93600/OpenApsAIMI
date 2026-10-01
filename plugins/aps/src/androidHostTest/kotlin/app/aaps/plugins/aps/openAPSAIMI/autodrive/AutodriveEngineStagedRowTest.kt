package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveAuditor
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataLake
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.MechanismAttentionGate
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * One training row per tick, and it is the row of the path that decided the dose.
 *
 * [AutodriveEngine] stages a row instead of writing it, because `tick` is reached from several
 * places in the same loop pass. The rule the staging slot exists for is that an **engaged** row must
 * never be replaced by a shadow row carrying the same tick id: the shadow paths do not reach the
 * pump, and a corpus that labels the engaged decision as a shadow one teaches the classifier the
 * opposite of what happened.
 *
 * The slot used to be a `java.util.concurrent.atomic.AtomicReference` and `getAndUpdate` did the
 * read-decide-swap in one call. `kotlin.concurrent.atomics` has no `getAndUpdate`, so the engine
 * spells the loop out, and this test is what says the loop still keeps the rule.
 */
class AutodriveEngineStagedRowTest : TestBase() {

    @Mock private lateinit var estimator: ContinuousStateEstimator
    @Mock private lateinit var mpc: MpcController
    @Mock private lateinit var attention: MechanismAttentionGate
    @Mock private lateinit var dataLake: AutodriveDataLake
    @Mock private lateinit var dataBackfiller: AutodriveDataBackfiller

    private val state = AutoDriveState(
        bg = 150.0,
        bgVelocity = -1.0,
        iob = 2.0,
        estimatedSI = 30.0 / 10000.0,
        estimatedRa = 1.0,
        physiologicalStressMask = DoubleArray(0),
        maxIOB = 60.0,
    )

    private val request = AutoDriveCommand(
        scheduledMicroBolus = 6.0,
        temporaryBasalRate = 1.2,
        isSafe = true,
        reason = "test",
    )

    private fun engine(): AutodriveEngine {
        whenever(attention.applyAttention(any())).thenAnswer { it.getArgument(0) }
        whenever(estimator.getLastRa()).thenReturn(state.estimatedRa)
        whenever(estimator.updateAndPredict(any(), any(), any())).thenAnswer { it.getArgument(0) }
        whenever(mpc.calculateOptimalDose(any(), any(), any())).thenReturn(request)
        val engine = AutodriveEngine(
            aapsLogger = aapsLogger,
            stateEstimator = estimator,
            mpcController = mpc,
            safetyShield = ControlBarrierShield(aapsLogger),
            onlineLearner = OnlineLearner(aapsLogger),
            autodriveAuditor = AutodriveAuditor(),
            dataLake = dataLake,
            dataBackfiller = dataBackfiller,
            attentionGate = attention,
        )
        engine.setShadowMode(false)
        engine.setIsActive(true)
        return engine
    }

    private fun AutodriveEngine.runTick(tickId: Long, engaged: Boolean): AutoDriveCommand? = tick(
        currentState = state,
        profileBasal = 1.0,
        profileIsf = 30.0,
        lgsThreshold = 70.0,
        hour = 3,
        steps = 0,
        hr = 60,
        rhr = 58,
        currentEpochMs = 1_000L * tickId,
        tickId = tickId,
        observationId = tickId,
        engaged = engaged,
    )

    @Test
    fun `a shadow pass does not replace the engaged row of the same tick`() {
        val engine = engine()

        engine.runTick(tickId = 7L, engaged = true)
        engine.runTick(tickId = 7L, engaged = false)
        engine.flushTickRow(tickId = 7L)

        verify(dataLake).recordSnapshot(any(), any(), any(), eq(true), any())
        verify(dataLake, never()).recordSnapshot(any(), any(), any(), eq(false), any())
    }

    @Test
    fun `an engaged pass replaces the shadow row of the same tick`() {
        val engine = engine()

        engine.runTick(tickId = 8L, engaged = false)
        engine.runTick(tickId = 8L, engaged = true)
        engine.flushTickRow(tickId = 8L)

        verify(dataLake).recordSnapshot(any(), any(), any(), eq(true), any())
        verify(dataLake, never()).recordSnapshot(any(), any(), any(), eq(false), any())
    }

    /** A new tick starts fresh: the previous tick's engaged row must not hold the slot. */
    @Test
    fun `a later tick gets its own row`() {
        val engine = engine()

        engine.runTick(tickId = 9L, engaged = true)
        engine.runTick(tickId = 10L, engaged = false)
        engine.flushTickRow(tickId = 10L)

        verify(dataLake).recordSnapshot(any(), any(), any(), eq(false), any())
        verify(dataLake, never()).recordSnapshot(any(), any(), any(), eq(true), any())
    }

    /** Exactly one row per tick reaches the corpus, whatever the flush is told. */
    @Test
    fun `a flush for another tick writes nothing`() {
        val engine = engine()

        engine.runTick(tickId = 11L, engaged = true)
        engine.flushTickRow(tickId = 12L)

        verify(dataLake, never()).recordSnapshot(any(), any(), any(), any(), any())
    }
}
