package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The shadow exit clamp must record what an unconditional relative bound would do, and must never
 * be mistaken for something that applies. See `docs/adr/0008-isf-decision-architecture.md`.
 */
class IsfSourceTelemetryTest {

    @BeforeEach
    fun reset() {
        IsfSourceTelemetry.reset()
    }

    @Test
    fun `shadow clamp reports no change when the value is already inside the band`() {
        IsfSourceTelemetry.recordProfileRelativeShadow(blendedMgdl = 35.0, profileIsfMgdl = 30.0)

        assertThat(IsfSourceTelemetry.lastProfileRelativeShadowMgdl).isWithin(1e-9).of(35.0)
        assertThat(IsfSourceTelemetry.lastProfileRelativeBoundHit).isFalse()
    }

    @Test
    fun `shadow clamp reports the bound it would apply below the band`() {
        // x0.32 of profile was observed in production; the band starts at x0.5.
        IsfSourceTelemetry.recordProfileRelativeShadow(blendedMgdl = 9.6, profileIsfMgdl = 30.0)

        assertThat(IsfSourceTelemetry.lastProfileRelativeShadowMgdl).isWithin(1e-9).of(15.0)
        assertThat(IsfSourceTelemetry.lastProfileRelativeBoundHit).isTrue()
    }

    @Test
    fun `shadow clamp reports the bound it would apply above the band`() {
        // x2.15 of profile was observed; the band ends at x2.0.
        IsfSourceTelemetry.recordProfileRelativeShadow(blendedMgdl = 64.5, profileIsfMgdl = 30.0)

        assertThat(IsfSourceTelemetry.lastProfileRelativeShadowMgdl).isWithin(1e-9).of(60.0)
        assertThat(IsfSourceTelemetry.lastProfileRelativeBoundHit).isTrue()
    }

    @Test
    fun `shadow clamp stays silent on unusable inputs rather than inventing a bound`() {
        IsfSourceTelemetry.recordProfileRelativeShadow(blendedMgdl = 40.0, profileIsfMgdl = 0.0)

        assertThat(IsfSourceTelemetry.lastProfileRelativeShadowMgdl).isNull()
        assertThat(IsfSourceTelemetry.lastProfileRelativeBoundHit).isNull()
    }

    @Test
    fun `the stress floor value is written on an active tick, armed or not`() {
        // It used to be dropped whenever it equalled the commanded value, and while the key is armed
        // those two are equal by construction: the field was null on 0 of 159, 0 of 953 and 0 of 710
        // active ticks of three packages.
        IsfSourceTelemetry.recordStressIsfFloor(
            active = true,
            reason = "active hr=92 rest=69 excess=23 steps15=0 held=12.0min",
            flooredIsfMgdl = 60.0,
            awakeRestingBpm = 69,
        )

        assertThat(IsfSourceTelemetry.lastStressIsfFloorActive).isTrue()
        assertThat(IsfSourceTelemetry.lastStressIsfFloorIsfMgdl).isWithin(1e-9).of(60.0)
        assertThat(IsfSourceTelemetry.lastStressIsfFloorAwakeRestingBpm).isEqualTo(69)
    }

    @Test
    fun `a gesture standing down for want of data records no baseline`() {
        IsfSourceTelemetry.recordStressIsfFloor(
            active = false,
            reason = "no_hr hr=80 rest=0 steps15=0",
            flooredIsfMgdl = null,
            awakeRestingBpm = null,
        )

        assertThat(IsfSourceTelemetry.lastStressIsfFloorActive).isFalse()
        assertThat(IsfSourceTelemetry.lastStressIsfFloorIsfMgdl).isNull()
        assertThat(IsfSourceTelemetry.lastStressIsfFloorAwakeRestingBpm).isNull()
    }

    @Test
    fun `band stays wide enough to be a domain guard, not a tuning knob`() {
        assertThat(IsfSourceTelemetry.PROFILE_RELATIVE_LOW).isAtMost(0.6)
        assertThat(IsfSourceTelemetry.PROFILE_RELATIVE_HIGH).isAtLeast(1.6)
    }
}
