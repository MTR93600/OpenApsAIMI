package app.aaps.plugins.main.general.dashboard.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DashboardV2StatusValueResolverTest {

    @Test
    fun `complete temporary target takes priority over profile`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 110.0,
            temporaryHigh = 120.0,
            profileLow = 90.0,
            profileHigh = 100.0,
        )

        assertThat(result).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(110.0, 120.0))
    }

    @Test
    fun `profile target is used without temporary target`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = null,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 105.0,
        )

        assertThat(result).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 105.0))
    }

    @Test
    fun `incomplete temporary target does not mix with profile values`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 110.0,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 105.0,
        )

        assertThat(result).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 105.0))
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
        assertThat(result).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(84.0, 84.0))
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

        assertThat(result).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(140.0, 160.0))
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
        assertThat(neverRun).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 100.0))

        // RT.targetBG defaults to 0.0 when never computed (APSResult.targetBG's non-null fallback) — not a
        // real target, must not be displayed as one.
        val zeroTarget = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = null,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = 100.0,
            aimiTargetBg = 0.0,
        )
        assertThat(zeroTarget).isEqualTo(DashboardV2StatusValueResolver.TargetRangeMgdl(90.0, 100.0))
    }

    @Test
    fun `incomplete available targets return no range`() {
        val result = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = 110.0,
            temporaryHigh = null,
            profileLow = 90.0,
            profileHigh = null,
        )

        assertThat(result).isNull()
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

        assertThat(result).isEqualTo(1.25)
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

        assertThat(result).isWithin(0.000_001).of(1.2)
    }

    @Test
    fun `scheduled basal is used without temporary basal`() {
        val result = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = null,
            scheduledBasalRateUh = 0.8,
        )

        assertThat(result).isEqualTo(0.8)
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

        assertThat(result).isNull()
    }
}
