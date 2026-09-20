package app.aaps.plugins.aps.openAPSAIMI.smb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmbMpcPiBlendTest {

    @Test
    fun `computeActMissing never negative when future activity exceeds current demand`() {
        val actMissing = SmbMpcPiBlend.computeActMissing(
            delta = 3.0,
            actCurr = 0.8,
            actFuture = 0.5,
            smbToGive = 0.6,
            actTarget = 1.0,
        )
        assertEquals(0.0, actMissing, 1e-9)
    }

    @Test
    fun `fast rise at normoglycemia uses bg score floor not pinned 0_5`() {
        val score = SmbMpcPiBlend.computeDeltaScore(
            delta = 5.0,
            bg = 100.0,
            targetBg = 100.0,
            postHypoRecent = false,
        )
        assertEquals(SmbMpcPiBlend.FAST_RISE_BG_SCORE_FLOOR, score, 1e-9)
        val alpha = SmbMpcPiBlend.computeAlpha(score)
        assertEquals(0.6, alpha, 1e-9)
    }

    @Test
    fun `fast rise after recent hypo forces balanced blend`() {
        val score = SmbMpcPiBlend.computeDeltaScore(
            delta = 5.0,
            bg = 100.0,
            targetBg = 100.0,
            postHypoRecent = true,
        )
        assertEquals(0.0, score, 1e-9)
        assertEquals(0.5, SmbMpcPiBlend.computeAlpha(score), 1e-9)
    }

    @Test
    fun `blend treats negative PI as zero not as withdrawal`() {
        val withNegativePi = SmbMpcPiBlend.blendMpcPi(
            optimalBasalMpc = 5.0,
            piDose = -2.23,
            alpha = 0.68,
        )
        val withZeroPi = SmbMpcPiBlend.blendMpcPi(
            optimalBasalMpc = 5.0,
            piDose = 0.0,
            alpha = 0.68,
        )
        assertEquals(withZeroPi, withNegativePi, 1e-9)
        assertEquals(3.4, withZeroPi, 0.05)
    }

    @Test
    fun `slow rise uses bg proportional delta score`() {
        val score = SmbMpcPiBlend.computeDeltaScore(
            delta = 3.0,
            bg = 119.0,
            targetBg = 100.0,
            postHypoRecent = false,
        )
        assertEquals(0.19, score, 0.01)
    }
}
