package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation.Level
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RiseConfirmationTest {

    @Test
    fun `above 130 two rising ticks confirm`() {
        val result = RiseConfirmation.evaluate(listOf(160.0, 155.0, 150.0))!!
        assertThat(result.windowTicks).isEqualTo(2)
        assertThat(result.level).isEqualTo(Level.CONFIRMED)
    }

    @Test
    fun `above 130 a flat tick after a rise is not confirmed`() {
        val result = RiseConfirmation.evaluate(listOf(160.0, 160.0, 150.0))!!
        assertThat(result.level).isEqualTo(Level.NONE)
    }

    @Test
    fun `above 130 a falling latest tick is none`() {
        assertThat(RiseConfirmation.evaluate(listOf(158.0, 160.0, 150.0))!!.level).isEqualTo(Level.NONE)
    }

    @Test
    fun `at or below 130 the window is 30 minutes`() {
        val steady = listOf(125.0, 121.0, 117.0, 113.0, 109.0, 105.0, 101.0)
        val result = RiseConfirmation.evaluate(steady)!!
        assertThat(result.windowTicks).isEqualTo(6)
        assertThat(result.level).isEqualTo(Level.CONFIRMED)
    }

    @Test
    fun `at or below 130 a single jump is only weak`() {
        val spike = listOf(125.0, 117.0, 117.0, 117.0, 117.0, 117.0, 117.0)
        assertThat(RiseConfirmation.evaluate(spike)!!.level).isEqualTo(Level.WEAK)
    }

    @Test
    fun `at or below 130 a flat history is none`() {
        val flat = listOf(120.0, 120.0, 121.0, 120.0, 119.0, 120.0, 120.0)
        assertThat(RiseConfirmation.evaluate(flat)!!.level).isEqualTo(Level.NONE)
    }

    @Test
    fun `short history returns null so the caller keeps its old behavior`() {
        assertThat(RiseConfirmation.evaluate(listOf(120.0, 118.0, 116.0))).isNull()
        assertThat(RiseConfirmation.evaluate(emptyList())).isNull()
    }

    private fun gater(): AutoDriveGater {
        val healthRepo: HealthContextRepository = mock()
        whenever(healthRepo.fetchSnapshotForAutodriveGater()).thenReturn(HealthContextSnapshot())
        return AutoDriveGater(healthRepo, mock<AAPSLogger>())
    }

    private val none = RiseConfirmation.Result(Level.NONE, 2, 0.0, 0.0)
    private val weak = RiseConfirmation.Result(Level.WEAK, 2, 1.0, 4.0)
    private val confirmed = RiseConfirmation.Result(Level.CONFIRMED, 2, 1.0, 10.0)

    @Test
    fun `a plateau without confirmation waits`() {
        val result = gater().shouldEngageV3(bg = 160.0, combinedDelta = 0.4, riseConfirmation = none)
        assertThat(result.engage).isFalse()
        assertThat(result.kind).isEqualTo(GateKind.AWAITING_CONFIRMATION)
    }

    @Test
    fun `a plateau with confirmation engages`() {
        val result = gater().shouldEngageV3(bg = 160.0, combinedDelta = 0.4, riseConfirmation = confirmed)
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `very high glucose is corrected without confirmation`() {
        val result = gater().shouldEngageV3(bg = 220.0, combinedDelta = 0.0, riseConfirmation = none)
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `a declared meal is never held back`() {
        val result = gater().shouldEngageV3(bg = 160.0, combinedDelta = 1.5, explicitMealMode = true, riseConfirmation = none)
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `no confirmation input keeps the old gate`() {
        val result = gater().shouldEngageV3(bg = 160.0, combinedDelta = 0.4)
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `an implicit meal context is never held back`() {
        val result = gater().shouldEngageV3(bg = 160.0, combinedDelta = 0.1, cob = 12.0, riseConfirmation = none)
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `implicit meal after zero basal waits for sustained rise`() {
        val result = gater().shouldEngageV3(
            bg = 110.0,
            combinedDelta = 4.0,
            cob = 12.0,
            riseConfirmation = none,
            postLgsRecovery = true,
        )
        assertThat(result.engage).isFalse()
        assertThat(result.kind).isEqualTo(GateKind.AWAITING_CONFIRMATION)
    }

    @Test
    fun `implicit meal after zero basal opens once rise is confirmed`() {
        val result = gater().shouldEngageV3(
            bg = 110.0,
            combinedDelta = 4.0,
            cob = 12.0,
            riseConfirmation = confirmed,
            postLgsRecovery = true,
        )
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `early rising meal after zero basal opens without waiting for full confirmation`() {
        val result = gater().shouldEngageV3(
            bg = 110.0,
            combinedDelta = 4.0,
            cob = 12.0,
            riseConfirmation = weak,
            postLgsRecovery = true,
        )
        assertThat(result.engage).isTrue()
    }

    @Test
    fun `declared meal bypasses post zero basal wait`() {
        val result = gater().shouldEngageV3(
            bg = 100.0,
            combinedDelta = 0.5,
            explicitMealMode = true,
            riseConfirmation = none,
            postLgsRecovery = true,
        )
        assertThat(result.engage).isTrue()
    }
}
