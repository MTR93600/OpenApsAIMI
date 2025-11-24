package app.aaps.plugins.aps.openAPSAIMI.wcycle

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class WCycleFacadeTest {

    private val adjuster: WCycleAdjuster = mockk(relaxed = true)
    private val logger: WCycleCsvLogger = mockk(relaxed = true)
    private val facade = WCycleFacade(adjuster, logger)

    @Test
    fun `test infoAndLog`() {
        val info = WCycleInfo(
            enabled = true,
            dayInCycle = 1,
            phase = CyclePhase.FOLLICULAR,
            baseBasalMultiplier = 1.0,
            baseSmbMultiplier = 1.0,
            learnedBasalMultiplier = 1.0,
            learnedSmbMultiplier = 1.0,
            basalMultiplier = 1.0,
            smbMultiplier = 1.0,
            applied = true,
            reason = "test"
        )
        every { adjuster.getInfo() } returns info
        every { logger.append(any()) } returns true
        
        val result = facade.infoAndLog(emptyMap())
        assertEquals(info, result)
    }
}
