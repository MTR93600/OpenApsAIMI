package app.aaps.ui.compose.dashboard.glass

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test

class DashboardV2StatusValueResolverTest {

    @Test
    fun `complete temporary target takes priority over profile`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 110.0,
            temporaryHigh = 120.0,
            profileLow = 90.0,
            profileHigh = 100.0,
        )

        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(110.0, 120.0), result)
    }

    @Test
    fun `profile target is used without temporary target`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = null,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 105.0,
        )

        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 105.0), result)
    }

    @Test
    fun `incomplete temporary target does not mix with profile values`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 110.0,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 105.0,
        )

        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 105.0), result)
    }

    @Test
    fun `AIMI-adjusted target is used over the raw profile target when no temporary target is active`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = null,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 100.0,
            aimiTargetBg = 84.0,
        )

        // A single point, not a range: low == high, matching what toTargetRangeString collapses to one number.
        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(84.0, 84.0), result)
    }

    @Test
    fun `an active temporary target still wins over the AIMI-adjusted target`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 140.0,
            temporaryHigh = 160.0,
            profileLow = 90.0,
            profileHigh = 100.0,
            aimiTargetBg = 84.0,
        )

        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(140.0, 160.0), result)
    }

    @Test
    fun `a zero or missing AIMI target falls back to the profile range`() {
        val neverRun = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = null,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 100.0,
            aimiTargetBg = null,
        )
        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 100.0), neverRun)

        // RT.targetBG defaults to 0.0 when never computed (APSResult.targetBG's non-null fallback) — not a
        // real target, must not be displayed as one.
        val zeroTarget = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = null,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 100.0,
            aimiTargetBg = 0.0,
        )
        assertEquals(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 100.0), zeroTarget)
    }

    @Test
    fun `incomplete available targets return no range`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 110.0,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = null,
        )

        assertNull(result)
    }

    @Test
    fun `absolute temporary basal is already units per hour`() {
        val result = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = DashboardV2StatusValueResolver.TemporaryBasal(
                rate = 1.25,
                isAbsolute = true,
            ),
            scheduledBasalRateUh = 0.8,
        )

        assertEquals(1.25, result)
    }

    @Test
    fun `percentage temporary basal is converted from scheduled basal`() {
        val result = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = DashboardV2StatusValueResolver.TemporaryBasal(
                rate = 150.0,
                isAbsolute = false,
            ),
            scheduledBasalRateUh = 0.8,
        )

        assertEquals(1.2, assertNotNull(result), 0.000_001)
    }

    @Test
    fun `scheduled basal is used without temporary basal`() {
        val result = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = null,
            scheduledBasalRateUh = 0.8,
        )

        assertEquals(0.8, result)
    }

    @Test
    fun `percentage temporary basal without scheduled basal returns no value`() {
        val result = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = DashboardV2StatusValueResolver.TemporaryBasal(
                rate = 150.0,
                isAbsolute = false,
            ),
            scheduledBasalRateUh = null,
        )

        assertNull(result)
    }
}
