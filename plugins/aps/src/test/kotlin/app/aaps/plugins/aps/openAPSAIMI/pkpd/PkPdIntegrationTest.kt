package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PkPdIntegrationTest {

    private val preferences: Preferences = mockk(relaxed = true)
    private val integration = PkPdIntegration(preferences)

    @Test
    fun `test computeRuntime disabled`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns false
        
        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )
        assertNull(result)
    }

    @Test
    fun `test computeRuntime enabled`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        // Mock other preferences to return defaults (relaxed mock handles primitives, but let's be safe)
        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 4.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin) } returns 75.0
        
        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )
        assertNotNull(result)
    }
}
