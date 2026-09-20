package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AuditorStableContextGuardTest {

    @Test
    fun confirmDowngradedToSoftenOnStableHighTbr() {
        val verdict = AuditorVerdict(
            verdict = VerdictType.Confirm,
            confidence = 0.9,
            degradedMode = false,
            riskFlags = emptyList(),
            evidence = emptyList(),
            boundedAdjustments = BoundedAdjustments(
                smbFactorClamp = 1.0,
                intervalAddMin = 0,
                preferTbr = false,
                tbrFactorClamp = 1.0,
            ),
            debugChecks = emptyList(),
        )
        val adjusted = AuditorStableContextGuard.adjustIfNeeded(
            verdict = verdict,
            bgMgdl = 118.0,
            deltaMgdl5m = 0.6,
            tbrRateUph = 6.0,
            profileBasalUph = 0.7,
        )
        assertThat(adjusted.verdict).isEqualTo(VerdictType.Soften)
        assertThat(adjusted.riskFlags).contains("STABLE_BG_HIGH_TBR")
    }
}
