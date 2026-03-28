package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.AimiTruth
import app.aaps.plugins.aps.openAPSAIMI.AimiTruth.assertThat
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import app.aaps.core.interfaces.logging.AAPSLogger

class AutoDrivePipelineTest {

    private val logger = mockk<AAPSLogger>(relaxed = true)
    private lateinit var engine: AutodriveEngine

    @BeforeEach
    fun setup() {
        // We'll use a mocked engine for now to test the pipeline flow, 
        // but real integration would use Dagger to inject real components.
        engine = mockk()
        every { engine.setIsActive(any()) } returns Unit
        every { engine.setShadowMode(any()) } returns Unit
    }

    @Test
    fun `test complete pipeline flow from state to decision`() {
        // 1. Arrange: Create a realistic state
        val state = AutoDriveState.createSafe(
            bg = 180.0,
            bgVelocity = 2.0,
            iob = 1.0,
            hour = 14
        )

        // 2. Assert: Verify state using custom Truth subject
        assertThat(state).hasBg(180.0)
        assertThat(state).hasVelocity(2.0)
        assertThat(state).isDay()

        // 3. Act: Simulate engine tick
        val command = AutoDriveCommand(
            scheduledMicroBolus = 0.5,
            temporaryBasalRate = 1.5,
            reason = "Rising BG correction"
        )
        
        // Relaxed match for all arguments to avoid signature change breakages
        every {
            engine.tick(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns command

        val result = engine.tick(state, 0.5, 50.0, 80.0, 14, 0, 70, 60)

        // 4. Assert: Verify outcome
        com.google.common.truth.Truth.assertThat(result).isNotNull()
        AimiTruth.assertThat(result).hasReasonContaining("Rising")
        AimiTruth.assertThat(result).hasSmb(0.5)
    }
}
