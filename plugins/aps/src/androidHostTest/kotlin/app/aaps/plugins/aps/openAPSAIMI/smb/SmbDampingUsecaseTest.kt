package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDampingAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Ported from the reference test on `dev_OAPSAIMI`. It stubs the final class [PkPdRuntime], and
 * Mockito is JVM only, so it lives in `androidHostTest`.
 */
class SmbDampingUsecaseTest {

    @Test
    fun `test run without pkpdRuntime`() {
        val input = SmbDampingUsecase.Input(
            smbDecision = 1.0,
            exercise = false,
            suspectedLateFatMeal = false,
            mealModeRun = false,
            highBgRiseActive = false
        )
        val output = SmbDampingUsecase.run(null, input)
        assertEquals(1.0, output.smbAfterDamping, 0.0)
    }

    @Test
    fun `test run with pkpdRuntime`() {
        val pkpdRuntime = mock<PkPdRuntime>()
        val audit = SmbDampingAudit(
            out = 0.8,
            tailApplied = false,
            tailMult = 1.0,
            activityRelief = 0.0,
            activityStage = InsulinActivityStage.PEAK,
            exerciseApplied = false,
            exerciseMult = 1.0,
            lateFatApplied = false,
            lateFatMult = 1.0,
            mealBypass = false
        )
        whenever(pkpdRuntime.dampSmbWithAudit(any(), any(), any(), any(), any())).thenReturn(audit)

        val input = SmbDampingUsecase.Input(
            smbDecision = 1.0,
            exercise = false,
            suspectedLateFatMeal = false,
            mealModeRun = true,
            highBgRiseActive = false
        )
        val output = SmbDampingUsecase.run(pkpdRuntime, input)
        assertEquals(0.8, output.smbAfterDamping, 0.0)
        assertEquals(audit, output.audit)
    }
}
