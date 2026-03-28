package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll

class AutodriveSILTest {

    private val logger: AAPSLogger = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)
    private val auditor: app.aaps.plugins.aps.openAPSAIMI.autodrive.advisor.AutodriveAuditor = mockk(relaxed = true)
    private val dataLake: app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataLake = mockk(relaxed = true)
    private val backfiller: app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller = mockk(relaxed = true)
    private val attentionGate: app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.MechanismAttentionGate = mockk(relaxed = true)

    private lateinit var stateEstimator: ContinuousStateEstimator
    private lateinit var mpcController: MpcController
    private lateinit var safetyShield: ControlBarrierShield
    private lateinit var onlineLearner: OnlineLearner
    private lateinit var engine: AutodriveEngine

    @BeforeEach
    fun setUp() {
        every { preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep) } returns 0.065
        stateEstimator = ContinuousStateEstimator(logger)
        mpcController = MpcController(logger, preferences)
        safetyShield = ControlBarrierShield(logger)
        onlineLearner = OnlineLearner(logger)

        engine = AutodriveEngine(
            aapsLogger = logger,
            stateEstimator = stateEstimator,
            mpcController = mpcController,
            safetyShield = safetyShield,
            onlineLearner = onlineLearner,
            autodriveAuditor = auditor,
            dataLake = dataLake,
            dataBackfiller = backfiller,
            attentionGate = attentionGate
        )

        // Mock attention gate to return same state
        every { attentionGate.applyAttention(any()) } answers { it.invocation.args[0] as AutoDriveState }
        
        // Active manuellement l'Autodrive pour le test sans les préférences UI
        engine.setShadowMode(false)
        engine.setIsActive(true)
    }

    @Test
    fun `test SIL Scenario 1 - Unannounced Meal`() {
        // Le repas furtif : Glucides avalés incognito, BG monte à 140 mg/dL avec vélocité +8.
        val state = AutoDriveState(
            bg = 140.0,
            bgVelocity = 8.0,
            iob = 0.5,
            cob = 0.0, // Indétectable par COB classique (=0)
            estimatedSI = 1.0,
            estimatedRa = 0.0, // Le ContinuousStateEstimator devrait pop le Ra, mais on mocke ici l'entrée.
            physiologicalStressMask = doubleArrayOf()
        )

        val command = engine.tick(currentState = state, profileBasal = 1.0, profileIsf = 50.0, lgsThreshold = 80.0, hour = 12, steps = 0, hr = 70, rhr = 60)
        assertThat(command).isNotNull()
        
        // La dose calculée par le MPC (ou le mock temporaire) doit réagir de manière agressive.
        // Puisque BG (140) - 100 > 0, il demande 1.5x le profil.
        assertThat(command!!.temporaryBasalRate).isGreaterThan(1.0)
    }

    @Test
    fun `test SIL Scenario 2 - Aggressive Post-Prandial Exercise`() {
        // La marche rapide : Beaucoup d'insuline à bord mais BG plonge très vite.
        val state = AutoDriveState(
            bg = 95.0,
            bgVelocity = -5.0, // Baisse violente
            iob = 4.0,         // Gros IOB post-repas
            cob = 0.0,
            estimatedSI = 2.0, // La sensi a doublé musculairement
            estimatedRa = 0.0,
            physiologicalStressMask = doubleArrayOf()
        )

        val command = engine.tick(currentState = state, profileBasal = 1.0, profileIsf = 50.0, lgsThreshold = 80.0, hour = 12, steps = 2000, hr = 120, rhr = 60)
        assertThat(command).isNotNull()
        
        // Le CBF (ou le mock) doit couper l'insuline violemment pour amortir.
        // Puisque BG (95) - 100 < 0, il demande 0.5x.
        assertThat(command!!.temporaryBasalRate).isLessThan(1.0)
    }

    @Test
    fun `test SIL Scenario 3 - Brittle T3c Patient`() {
        // Le pancréas coupé : Ultra-brittle sans glucagon. Près de l'hypo (65 mg/dL)
        val state = AutoDriveState(
            bg = 65.0,
            bgVelocity = -2.0,
            iob = 1.0,
            cob = 0.0,
            estimatedSI = 1.5,
            estimatedRa = 0.0,
            physiologicalStressMask = doubleArrayOf()
        )

        val command = engine.tick(
            currentState = state,
            profileBasal = 1.0,
            profileIsf = 50.0,
            lgsThreshold = 80.0,
            hour = 12,
            steps = 0,
            hr = 70,
            rhr = 60
        )
        assertThat(command).isNotNull()
        
        // Le système DOIT empêcher toute administration supplémentaire et couper la basale.
        assertThat(command!!.temporaryBasalRate).isLessThan(1.0)
    }
    @Test
    fun `test SIL Scenario 4 - Dawn Guard Simulation`() {
        println("\n--- DAWN GUARD SIMULATION ---")
        
        // Scenario A: Morning Cortisol Spike (7 AM, Low Steps, Rising HR)
        val stateA = AutoDriveState(
            bg = 150.0,
            bgVelocity = 3.0,
            iob = 0.5,
            hour = 7,             // Window 5-9
            steps = 20,           // Very Low
            hr = 75,              // RHR + 10
            rhr = 65,
            patientWeightKg = 70.0,
            physiologicalStressMask = doubleArrayOf()
        )

        // Scenario B: Breakfast Meal (1 PM, Moderate Steps, Similar BG rise)
        val stateB = stateA.copy(
            hour = 13,            // Outside window
            steps = 400           // Moving
        )

        // 1. PSE Update (Learning Ra)
        val updatedA = stateEstimator.updateAndPredict(stateA)
        val updatedB = stateEstimator.updateAndPredict(stateB)

        println("Cortisol Ra: ${updatedA.estimatedRa}")
        println("Meal Ra: ${updatedB.estimatedRa}")

        // 2. MPC Calculation
        val commandA = mpcController.calculateOptimalDose(updatedA, profileBasal = 1.0, lgsThreshold = 80.0)
        val commandB = mpcController.calculateOptimalDose(updatedB, profileBasal = 1.0, lgsThreshold = 80.0)

        println("Cortisol Command: SMB=${commandA.scheduledMicroBolus}, TBR=${commandA.temporaryBasalRate}")
        println("Meal Command: SMB=${commandB.scheduledMicroBolus}, TBR=${commandB.temporaryBasalRate}")

        // In the current implementation (Baseline), they should be identical or very close
        // because Dawn Guard is NOT YET IMPLEMENTED.
        // This test serves as a baseline to measure the delta later.
        // assertThat(commandA.scheduledMicroBolus).isEqualTo(commandB.scheduledMicroBolus)
    }
}
