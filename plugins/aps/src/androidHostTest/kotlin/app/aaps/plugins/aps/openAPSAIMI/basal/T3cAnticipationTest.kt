package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.aps.Predictions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class T3cAnticipationTest {

    @Test
    fun `defensive envelope takes stepwise minimum`() {
        val p = Predictions(
            IOB = listOf(120, 118, 100, 90),
            COB = listOf(120, 125, 130, 128)
        )
        val env = T3cAnticipation.defensiveEnvelope(p, 120.0)
        assertEquals(listOf(120.0, 118.0, 100.0, 90.0), env)
    }

    @Test
    fun `minutes to soft hypo from envelope`() {
        val env = listOf(120.0, 115.0, 105.0, 88.0)
        val m = T3cAnticipation.minutesToFirstAtOrBelow(env, 90.0)
        assertEquals(15, m)
    }

    @Test
    fun `buildHints disabled when strength zero`() {
        val h = T3cAnticipation.buildHints(
            predictions = Predictions(IOB = listOf(120, 90)),
            bgNow = 120.0,
            lgsThresholdMgdl = 70.0,
            activationThreshold = 130.0,
            eventualBg = 200.0,
            strengthRaw = 0.0
        )
        assertEquals(0.0, h.strength, 1e-9)
        assertNull(h.minutesToSoftHypo)
    }

    @Test
    fun `buildHints derives hyper lead from aggressive envelope`() {
        val p = Predictions(
            IOB = listOf(120, 122, 118),
            COB = listOf(120, 135, 140)
        )
        val h = T3cAnticipation.buildHints(
            predictions = p,
            bgNow = 120.0,
            lgsThresholdMgdl = 70.0,
            activationThreshold = 130.0,
            eventualBg = null,
            strengthRaw = 0.55
        )
        assertNotNull(h.minutesToHyperExcursion)
        assertEquals(5, h.minutesToHyperExcursion)
    }

    @Test
    fun `blend only uplifts toward hyper eventual`() {
        val down = T3cAnticipation.blendProjectedForHyper(150.0, 100.0, 1.0)
        assertEquals(150.0, down, 1e-9)
        val up = T3cAnticipation.blendProjectedForHyper(150.0, 200.0, 0.5)
        assertEquals(175.0, up, 1e-9)
    }

    @Test
    fun `hypo lead multiplier reduces when nadir under target`() {
        val m = T3cAnticipation.hypoLeadMultiplier(
            T3cAnticipation.Hints(
                strength = 1.0,
                lgsThresholdMgdl = 70.0,
                minutesToSoftHypo = 10,
                defensiveNadirBg = 92.0,
                minutesToHyperExcursion = null
            ),
            targetBg = 100.0
        )
        assertTrue(m < 1.0)
        assertTrue(m >= 0.55)
    }
}
