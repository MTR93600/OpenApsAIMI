package app.aaps.wear.complications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiDialGeometryTest {
    @Test fun fixedClockAxes() {
        assertThat(AimiDialGeometry.angleDegrees(0, 0)).isWithin(0.0001).of(-90.0)
        assertThat(AimiDialGeometry.angleDegrees(3, 0)).isWithin(0.0001).of(0.0)
        assertThat(AimiDialGeometry.angleDegrees(6, 0)).isWithin(0.0001).of(90.0)
        assertThat(AimiDialGeometry.angleDegrees(9, 0)).isWithin(0.0001).of(180.0)
        assertThat(AimiDialGeometry.angleDegrees(12, 0)).isWithin(0.0001).of(-90.0)
    }

    @Test fun minutesKeepRealClockPosition() {
        assertThat(AimiDialGeometry.angleDegrees(3, 30)).isWithin(0.0001).of(15.0)
        val now = 1_000_000_000L
        assertThat(AimiDialGeometry.rollingAngleDegrees(now, now, 15.0)).isWithin(0.0001).of(0.0)
        assertThat(AimiDialGeometry.rollingAngleDegrees(now - 6L * 60L * 60L * 1000L, now, 15.0)).isWithin(0.0001).of(-180.0)
        assertThat(AimiDialGeometry.glucoseRadius(40.0)).isWithin(0.001f).of(AimiDialGeometry.GLUCOSE_INNER)
        assertThat(AimiDialGeometry.glucoseRadius(300.0)).isWithin(0.001f).of(AimiDialGeometry.GLUCOSE_OUTER)
    }

    @Test fun radialBandsAreIndependentAndOutwardMonotonic() {
        assertThat(AimiDialGeometry.bandsDoNotOverlap()).isTrue()
        assertThat(AimiDialGeometry.GLUCOSE_INNER - AimiDialGeometry.TBR_OUTER).isWithin(0.001f).of(0f)
        assertThat(AimiDialGeometry.glucoseRadius(180.0)).isGreaterThan(AimiDialGeometry.glucoseRadius(90.0))
        assertThat(AimiDialGeometry.tbrRadius(2.0, 3.0)).isGreaterThan(AimiDialGeometry.tbrRadius(1.0, 3.0))
        assertThat(AimiDialGeometry.tbrRadius(3.0, 3.0)).isAtMost(AimiDialGeometry.TBR_OUTER)
    }

    @Test fun smbKeepsTimestampAndDose() {
        assertThat(AimiDialGeometry.angleDegrees(6, 30)).isWithin(0.0001).of(105.0)
        assertThat(AimiDialGeometry.smbLabel(0.4)).isEqualTo("0.40 ед")
        // Close SMB labels move inward through separate radial lanes instead of piling up
        // against the clipped outer edge of the round display.
        assertThat(AimiDialGeometry.smbLabelRadius(1)).isLessThan(AimiDialGeometry.smbLabelRadius(0))
        assertThat(AimiDialGeometry.smbLabelRadius(4)).isLessThan(AimiDialGeometry.smbLabelRadius(1))
    }
}
