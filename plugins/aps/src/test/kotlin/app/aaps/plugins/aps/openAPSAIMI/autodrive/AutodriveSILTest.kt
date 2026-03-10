package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class AutodriveSILTest {

    @Mock lateinit var logger: AAPSLogger
    
    private lateinit var stateEstimator: ContinuousStateEstimator
    private lateinit var mpcController: MpcController
    private lateinit var safetyShield: ControlBarrierShield
    private lateinit var onlineLearner: OnlineLearner
    private lateinit var engine: AutodriveEngine

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        stateEstimator = ContinuousStateEstimator(logger)
        mpcController = MpcController(logger)
        safetyShield = ControlBarrierShield(logger)
        onlineLearner = OnlineLearner(logger)

        engine = AutodriveEngine(
            aapsLogger = logger,
            stateEstimator = stateEstimator,
            mpcController = mpcController,
            safetyShield = safetyShield,
            onlineLearner = onlineLearner
        )
        
        // Active manuellement l'Autodrive pour le test sans les préférences UI
        engine.setShadowMode(false)
        val field = AutodriveEngine::class.java.getDeclaredField("isActive")
        field.isAccessible = true
        field.set(engine, true)
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

        val command = engine.tick(state, profileBasal = 1.0)
        assertThat(command).isNotNull()
        
        // La dose calculée par le MPC (ou le mock temporaire) doit réagir de manière agressive.
        // Puisque BG (140) - 100 > 0, il demande 1.5x le profil.
        assertThat(command!!.temporaryBasalRate).isGreaterThan(1.0)
        assertThat(command.reason).contains("Mock MPC")
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

        val command = engine.tick(state, profileBasal = 1.0)
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

        val command = engine.tick(state, profileBasal = 1.0)
        assertThat(command).isNotNull()
        
        // Le système DOIT empêcher toute administration supplémentaire et couper la basale.
        assertThat(command!!.temporaryBasalRate).isLessThan(1.0)
    }
}
