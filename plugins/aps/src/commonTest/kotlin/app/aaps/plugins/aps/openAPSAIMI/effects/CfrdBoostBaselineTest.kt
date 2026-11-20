package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The CFRD heart rate boost against the awake resting rate (audit 2026-11-20). It may only go down
 * compared with the old baseline, and it is 0 when the awake resting rate was not measured.
 */
class CfrdBoostBaselineTest {

    @Test
    fun `no awake resting rate gives no boost`() {
        // Old code: 85 against the made-up 60 was a rise of 25, boost 0.35.
        assertEquals(0.0, cfrdBoostOf(AimiT3cHrReading(hrNow = 85, awakeRestingBpm = null, legacyRestingBpm = 60)))
    }

    @Test
    fun `a normal waking heart rate is not inflammation against the awake baseline`() {
        // Awake rest 78: a rise of 7, below the first band.
        assertEquals(0.0, cfrdBoostOf(AimiT3cHrReading(hrNow = 85, awakeRestingBpm = 78, legacyRestingBpm = 60)))
    }

    @Test
    fun `an awake baseline below the old one never raises the boost`() {
        // Awake rest 52 would give a rise of 25 (0.35); the old baseline 60 gives 17 (0.20). The smaller wins.
        assertEquals(0.20, cfrdBoostOf(AimiT3cHrReading(hrNow = 77, awakeRestingBpm = 52, legacyRestingBpm = 60)))
    }

    @Test
    fun `a real rise against both baselines keeps its boost`() {
        assertEquals(0.35, cfrdBoostOf(AimiT3cHrReading(hrNow = 110, awakeRestingBpm = 70, legacyRestingBpm = 55)))
    }

    @Test
    fun `no heart rate now gives no boost`() {
        assertEquals(0.0, cfrdBoostOf(AimiT3cHrReading(hrNow = 0, awakeRestingBpm = 70, legacyRestingBpm = 60)))
    }
}
